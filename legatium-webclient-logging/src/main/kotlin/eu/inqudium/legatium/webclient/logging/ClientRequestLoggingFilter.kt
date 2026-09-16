package eu.inqudium.legatium.webclient.logging

import eu.inqudium.legatium.common.AdapterName
import eu.inqudium.legatium.common.ClientActivation
import eu.inqudium.legatium.common.ClientIdentity
import eu.inqudium.legatium.common.ClientLoggingMetrics
import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.ClientStack
import eu.inqudium.legatium.common.CorrelationIdGenerator
import eu.inqudium.legatium.common.HeaderValueMasker
import eu.inqudium.legatium.common.NanoTimeSource
import eu.inqudium.legatium.common.RequestTarget
import eu.inqudium.legatium.common.declaredCharsetOrUtf8
import eu.inqudium.legatium.common.reportQuietly
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ExchangeFunction
import reactor.core.publisher.Mono
import reactor.util.context.ContextView

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
 * - **The caller's context comes from the Reactor Context, not from a thread:** the `ContextView` the
 *   caller subscribed with is captured at subscription and restored into thread-locals around the
 *   exchange line ([AmbientContextRestorer], ADR-0010), so the client line joins the server line on
 *   the event-loop thread that completes the body - where the blocking twin simply logs on the
 *   caller's thread.
 * - **Emission point:** the response BODY's terminal signal instead of a `close()` - the next section.
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
 * pass-through (`stage=wiring`); a tee that throws costs one buffer's capture (`stage=wiring`); the
 * terminal callbacks complete the exchange through the emitter, which confines emission failures
 * (`stage=emission`) and its host-meter updates (`stage=wiring`). Calls are never affected.
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
        /** The bound `adapter-logging.*` configuration; also decides the default [masker]. */
        private val properties: ClientLoggingProperties,
        /** Monotonic time for `adapter_duration_ms`; tests pin it, production passes [NanoTimeSource.SYSTEM]. */
        private val nanoTime: NanoTimeSource,
        /** Supplies the id a TRACELESS call sends (ADR-0002); production passes [CorrelationIdGenerator.DEFAULT]. */
        private val correlationIds: CorrelationIdGenerator,
        /** The host's registry the meters are consumed from; filters on one registry share one metrics owner (see below). */
        meterRegistry: MeterRegistry,
        /**
         * How masked header values render. Defaults to the masker the properties' `masking-key` selects
         * ([HeaderValueMasker.forKey]) - so a manually constructed filter honours a configured key
         * exactly like the auto-configured one; the auto-configuration passes the host's bean instead.
         */
        private val masker: HeaderValueMasker = HeaderValueMasker.forKey(properties.maskingKey),
    ) : ExchangeFilterFunction {
        /** Shared with the emitter and exposed for the tests; one owner per registry. */
        internal val metrics = ClientLoggingMetrics.forRegistry(meterRegistry, ClientStack.WEBCLIENT)

        /** Exposed for the tests, which swap the emitter's ambient restorer to drive its fail-open path. */
        internal val emitter = ExchangeLogEmitter(properties, nanoTime, metrics, masker)

        // Activation is the shared implementation (ADR-0003): identical semantics on both stacks by construction.
        private val activation = ClientActivation(properties)

        override fun filter(
            request: ClientRequest,
            next: ExchangeFunction,
        ): Mono<ClientResponse> {
            if (activation.shouldNotFilter(request.url())) {
                return next.exchange(request)
            }
            // Mono.deferContextual around EVERYTHING, not only the connector call: wiring, the arrival line
            // and the gauge then run once per SUBSCRIPTION, so a retrying outer filter that resubscribes this
            // Mono gets one exchange - and one line - per attempt instead of a completed exchange it cannot
            // reopen. And a downstream filter that THROWS while assembling its publisher (instead of
            // returning Mono.error) becomes THIS pipeline's error signal - invoked bare, the exception would
            // propagate synchronously past doOnError/doFinally, lose the exchange event and leak the gauge.
            // The contextual variant hands over the subscriber's Reactor Context - the caller's ambient
            // context the emission restores (ADR-0010) - which is the same for every attempt.
            return Mono.deferContextual { ambient ->
                val wiring = wireOrNull(request, ambient) ?: return@deferContextual next.exchange(request)
                val exchange = wiring.exchange
                val call =
                    try {
                        // The optional arrival line INSIDE the try: an Exception in it is confined in
                        // [ExchangeLogEmitter.logRequestStart] and can never become this pipeline's error
                        // signal - only an Error can escape, and it then takes the same way as one from
                        // the assembly below.
                        if (properties.logRequestStart) {
                            emitter.logRequestStart(exchange)
                        }
                        next.exchange(wiring.request)
                    } catch (e: Exception) {
                        // Thrown while assembling, inside this defer: routed into the chain below as the
                        // error signal, so the response operator completes the exchange.
                        Mono.error(e)
                    } catch (t: Throwable) {
                        // An Error is outside the fail-open promise ([failOpen]). Whether Reactor treats
                        // it as fatal (rethrown through subscribe) or turns it into the caller's error
                        // signal (a plain Error, which deferContextual routes like an exception), no
                        // operator of this filter exists yet to see it - so the gauge is closed here.
                        abandonExchange(exchange, t)
                        throw t
                    }
                // The response Mono's own operator ([ObservedResponse]): records and wraps the response,
                // moves the state through DELIVERING to RESPONDED only once the downstream has TAKEN the
                // response, and completes the exchange itself for an error, an empty completion or a
                // cancel by the caller before the body owns it.
                ObservedResponse(call, exchange, ::onResponse, ::cancelUnlessResponded, ::complete)
            }
        }

        /**
         * The response arrived: records it on the exchange and returns the response with its body wrapped -
         * the tee (when a capture exists) and the terminal hooks that complete the exchange. `mutate()`
         * copies status, headers, cookies and request; the body flux is transformed lazily, so nothing is
         * read here. Pure assembly, no host call: nothing in it can fail and strand the response. The
         * state transitions belong to [ObservedResponse], which calls this between them.
         */
        private fun onResponse(
            exchange: Exchange,
            response: ClientResponse,
        ): ClientResponse {
            exchange.response = response
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
        private fun wireOrNull(
            request: ClientRequest,
            ambient: ContextView,
        ): Wiring? =
            try {
                wireExchange(request, ambient)
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
         * Exactly-once: closes the gauge and emits, whichever terminal callback wins the transition. The
         * callbacks run inside Reactor's signal propagation, where an escaping exception would be rethrown
         * into the caller's pipeline: the emission is confined in the emitter (`stage=emission`, its
         * host-meter updates as `stage=wiring`), and the gauge close is a private counter that cannot
         * throw.
         */
        internal fun complete(exchange: Exchange) {
            if (exchange.state.getAndSet(ExchangeState.COMPLETED) == ExchangeState.COMPLETED) {
                return
            }
            finish(exchange)
        }

        /**
         * The caller cancelled the response Mono. Before a response (`OPEN`) or while the response is being
         * handed to a downstream that may drop it (`DELIVERING`) this ends the exchange as `cancelled`; once
         * the downstream has taken the response (`RESPONDED`) the body owns the completion and the cancel
         * is ignored - a host operator such as `next()` cancels the Mono from within the delivery, before
         * it hands the value on, and reaches [ObservedResponse] on the delivering thread, which does not
         * call this at all. The CAS loop makes the decision atomic against the delivering thread's own
         * transitions.
         */
        private fun cancelUnlessResponded(exchange: Exchange) {
            while (true) {
                val state = exchange.state.get()
                if (state == ExchangeState.RESPONDED || state == ExchangeState.COMPLETED) {
                    return
                }
                if (exchange.state.compareAndSet(state, ExchangeState.COMPLETED)) {
                    exchange.cancelled = true
                    finish(exchange)
                    return
                }
            }
        }

        /** Gauge close and emission, after the exactly-once transition was won. */
        private fun finish(exchange: Exchange) {
            metrics.exchangeCompleted()
            emitter.logExchange(exchange)
        }

        /**
         * Everything that must exist before the connector call runs: identity resolution, the captures, the
         * rebuilt outgoing request, the eagerly captured request-side coordinates and the gauge. Called
         * exclusively from [wireOrNull] - anything thrown here is confined there.
         */
        private fun wireExchange(
            request: ClientRequest,
            ambient: ContextView,
        ): Wiring {
            val headers = request.headers()
            val identity = ClientIdentity.resolve(headers, properties, correlationIds)
            // Guarded in [ClientLoggingMetrics.requestId]: a throwing host counter never fails the call.
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
                    name = AdapterName.of(request.attribute(ADAPTER_NAME_ATTRIBUTE).orElse(null)),
                    requestCapture = captures.request,
                    responseCapture = captures.response,
                    requestCharset = headers.declaredCharsetOrUtf8(),
                    startNanos = nanoTime.nanoTime(),
                    traceId = identity.traceId,
                    spanId = identity.spanId,
                    ambient = ambient,
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

            /**
             * Request attribute the host sets to NAME a client - the value of `adapter_name` and the
             * `name` tag of the body meters (ADR-0009). Set once per client, and every call of that client
             * carries it:
             *
             * ```kotlin
             * WebClient.builder().defaultRequest { it.attribute(ADAPTER_NAME_ATTRIBUTE, "billing") }
             * ```
             *
             * The same string on both twins, so a host carrying both jars names its clients with one
             * constant. A blank or non-string value counts as no name.
             */
            const val ADAPTER_NAME_ATTRIBUTE = AdapterName.ATTRIBUTE

            /** The cause attached to an exchange whose connector completed empty - WebClient's own message for the caller. */
            const val NO_RESPONSE_MESSAGE = "The underlying HTTP client completed without emitting a response"

            // The module's own logger, never the exchange logger: the exchange log stream stays parseable.
            private val internalLog = LoggerFactory.getLogger(ClientRequestLoggingFilter::class.java)
        }
    }
