package eu.inqudium.legatium.webclient.logging

import eu.inqudium.legatium.common.ClientActivation
import eu.inqudium.legatium.common.ClientIdentity
import eu.inqudium.legatium.common.ClientLoggingMetrics
import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.ClientStack
import eu.inqudium.legatium.common.CorrelationIdGenerator
import eu.inqudium.legatium.common.HeaderValueMasker
import eu.inqudium.legatium.common.NanoTimeSource
import eu.inqudium.legatium.common.RequestTarget
import eu.inqudium.legatium.common.reportQuietly
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ExchangeFunction
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType

/**
 * The WebClient twin of `legatium-restclient-logging`'s `ClientRequestLoggingInterceptor`: ONE
 * structured `adapter_*` line per outbound HTTP exchange, identical message and field format, identical
 * `adapter-logging.*` configuration (see [ClientLoggingProperties]). Stack-inherent differences to the
 * RestClient twin, all deliberate:
 *
 * - **Disposition vocabulary:** `cancelled` in addition to `success`/`failure`/`timeout` - a subscription
 *   the CALLER abandoned (a downstream `timeout()` operator, a disposed caller, a client that
 *   disconnected mid-stream) is the reactive reality a blocking call cannot have. A consumer that
 *   cancels the body from within its own delivery because it has read enough - Spring's body skip for
 *   `bodyToMono(Void.class)`, a `take(n)` - is NOT that: the exchange completes as `success` with the
 *   body partially read (see [ObservedBody]). Note the consequence: a `Mono.timeout()` the CALLER
 *   applies reaches this filter as a CANCEL and logs `cancelled`; a timeout the CONNECTOR raises
 *   (Reactor Netty's response timeout) arrives as an error signal and logs `timeout`.
 * - **No call-wide THREAD-LOCAL MDC:** the call hops event-loop threads; the exchange identity rides
 *   the emission's `MdcScope` (and the message inline). Handler-side propagation of the identity into
 *   reactive operators is the host's context-propagation business, not this filter's.
 * - **Emission point:** the response BODY's terminal signal instead of a `close()` - see below.
 *
 * ## Emission point: the body's terminal signal
 *
 * `WebClient` hands the caller a `ClientResponse` whose body is a `Flux` the caller (or the client's
 * own `retrieve`/`exchangeToMono` plumbing) subscribes to afterwards - that is when the bytes flow and
 * when the exchange is truly over. The filter therefore mutates the response so that its body carries
 * the tee and the terminal hooks: the event is emitted at the body's COMPLETE, ERROR or CANCEL. A call
 * that produces no response (connection refused, a connector timeout, a cancellation before the status
 * line) emits at the response `Mono`'s own error/cancel signal with `-> -` and no status. A response
 * whose body the application never subscribes to (and never releases) never completes - and stays open
 * on the `adapter.logging.exchanges.open` gauge, the module's liveness signal, rather than logging a
 * guess (every `retrieve`/`exchangeToMono`/`exchangeToFlux` path of `WebClient` subscribes or
 * releases; a raw `exchange()` caller owns that duty).
 *
 * ## Where it sits in the filter chain
 *
 * The auto-configuration appends this filter through a LATE customizer, so it runs INSIDE the filters
 * of earlier customizers and of the builder's own configuration: it sees the request as it goes to the
 * connector - after an authentication filter added its header, once per attempt of a retrying filter.
 * Filters the host adds AFTER the customizers ran run inside this one and are outside that guarantee.
 * The traceparent header is not affected by the order: the client observation injects it into the
 * request BEFORE the filter chain runs (pinned beside a real bridge by the tracing integration test).
 *
 * ## Fail-open, including the wiring and every callback
 *
 * Identical contract to the RestClient twin: a wiring failure degrades the filter to a plain
 * pass-through (`stage=wiring`); the terminal callbacks confine their own failures (`stage=wiring`) and
 * still complete the exchange; emission failures are confined in the emitter (`stage=emission`). Calls
 * are never affected.
 *
 * ## Manual wiring: filters on one `MeterRegistry` share one metrics owner
 *
 * The module's meters are identified by name, so all filters constructed against the same registry
 * share a single internal metrics owner: the counters and the `adapter.logging.exchanges.open` gauge
 * report totals ACROSS those filters, not per filter. The auto-configuration wires exactly one filter
 * per context, where the distinction never shows.
 */
class ClientRequestLoggingFilter
    @JvmOverloads
    constructor(
        private val properties: ClientLoggingProperties,
        private val nanoTime: NanoTimeSource,
        private val correlationIds: CorrelationIdGenerator,
        meterRegistry: MeterRegistry,
        /** How masked header values render; the auto-configuration passes the host's bean, [HeaderValueMasker.DEFAULT] otherwise. */
        private val masker: HeaderValueMasker = HeaderValueMasker.DEFAULT,
    ) : ExchangeFilterFunction {
        /** Shared with the emitter and exposed for the tests; one owner per registry. */
        internal val metrics = ClientLoggingMetrics.forRegistry(meterRegistry, ClientStack.WEBCLIENT)
        private val emitter = ExchangeLogEmitter(properties, nanoTime, metrics, masker)

        // Activation is the shared implementation (ADR-0003): identical semantics on both stacks by construction.
        private val activation = ClientActivation(properties)

        override fun filter(
            request: ClientRequest,
            next: ExchangeFunction,
        ): Mono<ClientResponse> {
            if (activation.shouldNotFilter(request.url())) {
                return next.exchange(request)
            }
            // Mono.defer around EVERYTHING, not only the connector call: wiring, the arrival line and the
            // gauge then run once per SUBSCRIPTION, so a retrying outer filter that resubscribes this Mono
            // gets one exchange - and one line - per attempt instead of a completed exchange it cannot
            // reopen. And a downstream filter that THROWS while assembling its publisher (instead of
            // returning Mono.error) becomes THIS pipeline's error signal - invoked bare, the exception would
            // propagate synchronously past doOnError/doFinally, lose the exchange event and leak the gauge.
            return Mono.defer {
                val wiring = wireOrNull(request) ?: return@defer next.exchange(request)
                val exchange = wiring.exchange
                if (properties.logRequestStart) {
                    emitter.logRequestStart(exchange)
                }
                val call =
                    try {
                        next.exchange(wiring.request)
                    } catch (e: Exception) {
                        // Thrown while assembling, inside this defer: routed into the chain below as the
                        // error signal, so doOnError/doFinally complete the exchange.
                        Mono.error(e)
                    } catch (t: Throwable) {
                        // An Error is outside the fail-open promise (see FailOpenDiagnostics) and, being
                        // fatal to Reactor, bypasses every signal hook - the gauge still closes.
                        abandonExchange(exchange, t)
                        throw t
                    }
                call
                    .map { response -> onResponse(exchange, response) }
                    .doOnError { exchange.failure = it }
                    // A cancel BEFORE the response is the caller walking away; once the response was
                    // delivered the body owns the exchange, and a host operator that cancels this Mono
                    // after onNext (a `next()`, a future bridge) must not end it here.
                    .doOnCancel { if (exchange.state.get() != ExchangeState.RESPONDED) exchange.cancelled = true }
                    .doFinally { signal ->
                        val state = exchange.state.get()
                        if (state == ExchangeState.RESPONDED) {
                            // A delivered response hands the completion to the body's terminal signal.
                            return@doFinally
                        }
                        if (signal == SignalType.ON_COMPLETE && state == ExchangeState.OPEN) {
                            // An EMPTY completion - a broken connector, a host filter that swallowed an error
                            // into Mono.empty() - is a failure: WebClient raises exactly this for the caller.
                            exchange.failure = IllegalStateException(NO_RESPONSE_MESSAGE)
                        }
                        complete(exchange)
                    }
            }
        }

        /**
         * The response arrived: records it on the exchange, moves the state to `RESPONDED`, and returns the
         * response with its body wrapped - the tee (when a capture exists) and the terminal hooks that
         * complete the exchange. `mutate()` copies status, headers, cookies and request; the body flux is
         * transformed lazily, so nothing is read here. Pure assembly, no host call: nothing in it can fail
         * and strand the response.
         */
        private fun onResponse(
            exchange: Exchange,
            response: ClientResponse,
        ): ClientResponse {
            exchange.response = response
            exchange.state.compareAndSet(ExchangeState.OPEN, ExchangeState.RESPONDED)
            val capture = exchange.responseCapture
            return response
                .mutate()
                .body { body -> ObservedBody(body, exchange, capture, ::complete, ::teeFailure) }
                .build()
        }

        /** A tee that threw cost the capture of one buffer - counted as wiring, the call untouched. */
        private fun teeFailure(e: Exception) {
            reportQuietly {
                metrics.wiringFailure()
                internalLog.warn("Response body tee failed - the logged body may be incomplete: {}", e.toString())
            }
        }

        /**
         * The exactly-once end of an exchange whose connector call left with an [Error]: the gauge closes,
         * no event is attempted, one WARN breadcrumb on the module's logger, quietly.
         */
        private fun abandonExchange(
            exchange: Exchange,
            error: Throwable,
        ) {
            if (exchange.state.getAndSet(ExchangeState.COMPLETED) == ExchangeState.COMPLETED) {
                return
            }
            reportQuietly {
                metrics.exchangeCompleted()
                internalLog.warn("Adapter http exchange abandoned: {} {} - {}", exchange.method, exchange.target, error.toString())
            }
        }

        /**
         * The fail-open wiring: an exception degrades the filter to a plain pass-through (the caller sees
         * null), counted `stage=wiring` - a logging component must never fail the call it describes.
         */
        private fun wireOrNull(request: ClientRequest): Wiring? =
            try {
                wireExchange(request)
            } catch (e: Exception) {
                reportQuietly {
                    metrics.wiringFailure()
                    internalLog.error(
                        "Client logging could not be wired for {} {} - continuing without logging: {}",
                        request.method(),
                        request.url(),
                        e.toString(),
                        e,
                    )
                }
                null
            }

        /**
         * Exactly-once: closes the gauge and emits, whichever terminal callback wins the transition. Guarded:
         * the callbacks run inside Reactor's signal propagation, where an escaping exception would be
         * rethrown into the caller's pipeline - a broken emission is confined in the emitter, and a broken
         * gauge is counted here.
         */
        internal fun complete(exchange: Exchange) {
            if (exchange.state.getAndSet(ExchangeState.COMPLETED) == ExchangeState.COMPLETED) {
                return
            }
            try {
                metrics.exchangeCompleted()
            } catch (e: Exception) {
                reportQuietly {
                    metrics.wiringFailure()
                    internalLog.warn("Open-exchange bookkeeping failed for {} {}: {}", exchange.method, exchange.target, e.toString())
                }
            }
            emitter.logExchange(exchange)
        }

        /**
         * Everything that must exist before the connector call runs: identity resolution, the captures, the
         * rebuilt outgoing request, the eagerly captured request-side coordinates and the gauge. Called
         * exclusively from [wireOrNull] - anything thrown here is confined there.
         */
        private fun wireExchange(request: ClientRequest): Wiring {
            val headers = request.headers()
            val identity = ClientIdentity.resolve(headers, properties, correlationIds)
            // Guarded inside the metrics: a throwing host counter must not turn the call into an unlogged
            // pass-through.
            metrics.requestId(identity.source)
            val captures = newCaptures()
            // The request the connector gets: the caller's, plus the correlation header on a traceless call
            // without one, plus the body tee when the request body is captured. ClientRequest is immutable,
            // so both go through a rebuild; untouched otherwise.
            val outgoing =
                request
                    .let { if (identity.sendCorrelationHeader) ClientRequest.from(it).headers { h -> h.set(properties.correlationIdHeader, identity.requestId) }.build() else it }
                    .let { if (captures.request != null) it.withRequestBodyTee(captures.request) else it }
            val outgoingHeaders = outgoing.headers()
            val target = RequestTarget.of(request.url())
            val exchange =
                Exchange(
                    method = request.method().name(),
                    target = target.target,
                    host = target.host,
                    path = target.path,
                    query = if (properties.includeQueryString) request.url().rawQuery else null,
                    requestId = identity.requestId,
                    // Multi-value resolution, natively from HttpHeaders - from the OUTGOING request, so a
                    // selected correlation header shows what actually goes out.
                    requestHeaders =
                        properties.requestHeaders.select(outgoingHeaders.headerNames(), masker) { name ->
                            outgoingHeaders[name]?.takeIf { it.isNotEmpty() }?.joinToString(", ")
                        },
                    uriTemplate = request.attribute(URI_TEMPLATE_ATTRIBUTE).orElse(null) as? String,
                    requestCapture = captures.request,
                    responseCapture = captures.response,
                    requestCharset = headers.declaredCharsetOrUtf8(),
                    startNanos = nanoTime.nanoTime(),
                    traceId = identity.traceId,
                    spanId = identity.spanId,
                )
            metrics.exchangeOpened()
            return Wiring(exchange, outgoing)
        }

        /**
         * A capture exists when the body is logged in ANY mode OR measured - `on-failure` needs the bytes
         * before the outcome is known and the emitter drops them on success; measure-only runs the capture
         * in count-only mode (limit 0: nothing buffered, every byte counted).
         */
        private fun newCaptures(): Captures =
            Captures(
                request = if (properties.logRequestBody.captures || properties.measureRequestBodySize) BoundedBodyCapture(if (properties.logRequestBody.captures) properties.maxBodyBytes else 0) else null,
                response = if (properties.logResponseBody.captures || properties.measureResponseBodySize) BoundedBodyCapture(if (properties.logResponseBody.captures) properties.maxBodyBytes else 0) else null,
            )

        private class Captures(
            val request: BoundedBodyCapture?,
            val response: BoundedBodyCapture?,
        )

        /** The wired [exchange] plus the (possibly rebuilt) request the connector receives. */
        internal class Wiring(
            val exchange: Exchange,
            val request: ClientRequest,
        )

        companion object {
            /**
             * Request attribute under which `WebClient` records the URI template of a call made through the
             * template form of `uri(...)`. Mirrors `DefaultWebClient.URI_TEMPLATE_ATTRIBUTE`
             * (`WebClient.class.getName() + ".uriTemplate"`), which is private - derived the same way
             * instead, so it matches the value the client sets (pinned by `UriTemplateAttributeTest`) and
             * stays absent for an expanded `URI`.
             */
            const val URI_TEMPLATE_ATTRIBUTE = "org.springframework.web.reactive.function.client.WebClient.uriTemplate"

            /** The cause attached to an exchange whose connector completed empty - WebClient's own message for the caller. */
            const val NO_RESPONSE_MESSAGE = "The underlying HTTP client completed without emitting a response"

            // Wiring failures go to the module's own logger, never onto the exchange logger - the exchange
            // log stream stays parseable.
            private val internalLog = LoggerFactory.getLogger(ClientRequestLoggingFilter::class.java)
        }
    }
