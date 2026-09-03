package eu.inqudium.legatium.webclient.logging

import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.CorrelationIdGenerator
import eu.inqudium.legatium.common.NanoTimeSource
import eu.inqudium.legatium.common.Traceparent
import eu.inqudium.legatium.common.reportQuietly
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.server.PathContainer
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType
import java.net.URI

/**
 * The WebClient twin of `legatium-restclient-logging`'s `ClientRequestLoggingInterceptor`: ONE
 * structured `client_*` line per outbound HTTP exchange, identical message and field format, identical
 * `client-logging.*` configuration (see [ClientLoggingProperties]). Stack-inherent differences to the
 * RestClient twin, all deliberate:
 *
 * - **Disposition vocabulary:** `cancelled` in addition to `success`/`failure`/`timeout` - a cancelled
 *   subscription (a downstream `timeout()` operator, a `take`, a disposed caller) is the reactive
 *   reality a blocking call cannot have. Note the consequence: a `Mono.timeout()` the CALLER applies
 *   reaches this filter as a CANCEL and logs `cancelled`; a timeout the CONNECTOR raises (Reactor
 *   Netty's response timeout) arrives as an error signal and logs `timeout`.
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
 * on the `client.logging.exchanges.open` gauge, the module's liveness signal, rather than logging a
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
 * share a single internal metrics owner: the counters and the `client.logging.exchanges.open` gauge
 * report totals ACROSS those filters, not per filter. The auto-configuration wires exactly one filter
 * per context, where the distinction never shows.
 */
class ClientRequestLoggingFilter(
    private val properties: ClientLoggingProperties,
    private val nanoTime: NanoTimeSource,
    private val correlationIds: CorrelationIdGenerator,
    meterRegistry: MeterRegistry,
) : ExchangeFilterFunction {
    /** Shared with the emitter and exposed for the tests; one owner per registry. */
    internal val metrics = ClientLoggingMetrics.forRegistry(meterRegistry)
    private val emitter = ExchangeLogEmitter(properties, nanoTime, metrics)

    // Parsed ONCE at construction: an invalid pattern is a configuration error and fails the context
    // start with the parser's message, instead of failing per call.
    private val includePathPatterns: List<PathPattern> =
        properties.includePathPatterns.map { PathPatternParser.defaultInstance.parse(it) }
    private val excludedHosts: Set<String> = properties.excludeHosts.map { it.lowercase() }.toSet()

    /**
     * The filter is active for a call when its host is not excluded, its path matches ANY include
     * pattern (empty includes = every call) and NO exclude prefix - an exclude always wins; identical
     * semantics with the RestClient twin (decoded segments for matching, raw path in the log).
     */
    internal fun shouldNotFilter(uri: URI): Boolean {
        if (excludedHosts.isNotEmpty() && uri.host?.lowercase() in excludedHosts) {
            return true
        }
        if (includePathPatterns.isEmpty() && properties.excludePathPrefixes.isEmpty()) {
            return false
        }
        val container = PathContainer.parsePath(uri.rawPath ?: "")
        if (includePathPatterns.isNotEmpty() && includePathPatterns.none { it.matches(container) }) {
            return true
        }
        if (properties.excludePathPrefixes.isEmpty()) {
            return false
        }
        val decodedPath =
            container.elements().joinToString("") { element ->
                if (element is PathContainer.PathSegment) element.valueToMatch() else element.value()
            }
        return properties.excludePathPrefixes.any { decodedPath.startsWith(it) }
    }

    override fun filter(
        request: ClientRequest,
        next: ExchangeFunction,
    ): Mono<ClientResponse> {
        if (shouldNotFilter(request.url())) {
            return next.exchange(request)
        }
        val wiring = wireOrNull(request) ?: return next.exchange(request)
        val exchange = wiring.exchange
        if (properties.logRequestStart) {
            emitter.logRequestStart(exchange)
        }
        // Mono.defer: a downstream filter that THROWS while assembling its publisher (instead of
        // returning Mono.error) must become THIS pipeline's error signal - invoked bare, the exception
        // would propagate synchronously past doOnError/doFinally, lose the exchange event and leak the
        // open-exchange gauge.
        return Mono
            .defer { next.exchange(wiring.request) }
            .map { response -> onResponse(exchange, response) }
            .doOnError { exchange.failure = it }
            .doOnCancel { exchange.cancelled = true }
            .doFinally { signal ->
                // A delivered response hands the completion to the body's terminal signal; everything
                // else (error, cancel, or an empty completion from a broken connector) ends here.
                if (signal != SignalType.ON_COMPLETE || exchange.state.get() != ExchangeState.RESPONDED) {
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
            .body { body ->
                Flux
                    .defer {
                        capture?.markStarted()
                        body
                    }.map { buffer -> if (capture == null) buffer else tee(capture, buffer) }
                    .doOnComplete { capture?.markCompleted() }
                    .doOnError { exchange.failure = it }
                    .doOnCancel { exchange.cancelled = true }
                    .doFinally { complete(exchange) }
            }.build()
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

    private fun wireExchange(request: ClientRequest): Wiring {
        val headers = request.headers()
        // The exchange identity, resolved per ADR-0002: a conformant traceparent's trace id IS the
        // request id (a correlation header the caller put on the request is ignored on such calls -
        // the distributed identity outranks the private one); only a traceless call accepts the
        // correlation header already on the request or generates a fresh id, and only a traceless
        // call without one gets the header ADDED - a traced call goes out observationally untouched.
        val trace = Traceparent.parse(headers.getFirst(Traceparent.HEADER))
        val headerCorrelationId =
            if (trace == null) {
                headers.getFirst(properties.correlationIdHeader)?.takeUnless { it.isBlank() }
            } else {
                null
            }
        val requestId = trace?.first ?: headerCorrelationId ?: correlationIds.nextCorrelationId()
        // Guarded inside the metrics: a throwing host counter must not turn the call into an unlogged
        // pass-through.
        metrics.requestId(
            when {
                trace != null -> ClientLoggingMetrics.REQUEST_ID_SOURCE_TRACE
                headerCorrelationId != null -> ClientLoggingMetrics.REQUEST_ID_SOURCE_HEADER
                else -> ClientLoggingMetrics.REQUEST_ID_SOURCE_GENERATED
            },
        )
        val sendCorrelationHeader = trace == null && headerCorrelationId == null

        // A capture exists when the body is logged OR measured; measure-only runs the capture in
        // count-only mode (limit 0: nothing buffered, every byte counted).
        val requestCapture =
            if (properties.logRequestBody || properties.measureRequestBodySize) {
                BoundedBodyCapture(if (properties.logRequestBody) properties.maxBodyBytes else 0)
            } else {
                null
            }
        val responseCapture =
            if (properties.logResponseBody || properties.measureResponseBodySize) {
                BoundedBodyCapture(if (properties.logResponseBody) properties.maxBodyBytes else 0)
            } else {
                null
            }

        // The request the connector gets: the caller's, plus the correlation header on a traceless call
        // without one, plus the body tee when the request body is captured. ClientRequest is immutable,
        // so both go through one rebuild; untouched otherwise.
        var outgoing = request
        if (sendCorrelationHeader) {
            outgoing = ClientRequest.from(outgoing).header(properties.correlationIdHeader, requestId).build()
        }
        if (requestCapture != null) {
            outgoing = outgoing.withRequestBodyTee(requestCapture)
        }
        val outgoingHeaders = outgoing.headers()

        // RAW (still percent-encoded) path and query, as they go on the wire - twin parity with the
        // RestClient module, and the log-injection guard: java.net.URI's decoded getPath()/getQuery()
        // turn `%0A`/`%0D` into real line breaks that would forge lines in every plain-text sink
        // (message, MDC client_route, fields). Activation matching keeps the decoded path.
        val uri = request.url()
        val host = uri.host?.let { if (uri.port != -1) "$it:${uri.port}" else it }
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val target =
            buildString {
                uri.scheme?.let { append(it).append("://") }
                host?.let { append(it) }
                append(path)
            }
        val exchange =
            Exchange(
                method = request.method().name(),
                target = target,
                host = host,
                path = path,
                query = if (properties.includeQueryString) uri.rawQuery else null,
                requestId = requestId,
                // Multi-value resolution, natively from HttpHeaders - from the OUTGOING request, so a
                // selected correlation header shows what actually goes out.
                requestHeaders =
                    properties.requestHeaders.select(outgoingHeaders.headerNames()) { name ->
                        outgoingHeaders[name]?.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    },
                uriTemplate = request.attribute(URI_TEMPLATE_ATTRIBUTE).orElse(null) as? String,
                requestCapture = requestCapture,
                responseCapture = responseCapture,
                requestCharset = headers.declaredCharsetOrUtf8(),
                startNanos = nanoTime.nanoTime(),
                traceId = trace?.first,
                spanId = trace?.second,
            )
        metrics.exchangeOpened()
        return Wiring(exchange, outgoing)
    }

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

        // Wiring failures go to the module's own logger, never onto the exchange logger - the exchange
        // log stream stays parseable.
        private val internalLog = LoggerFactory.getLogger(ClientRequestLoggingFilter::class.java)
    }
}
