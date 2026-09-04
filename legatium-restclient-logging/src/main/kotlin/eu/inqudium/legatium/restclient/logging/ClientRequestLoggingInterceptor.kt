package eu.inqudium.legatium.restclient.logging

import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.CorrelationIdGenerator
import eu.inqudium.legatium.common.HeaderValueMasker
import eu.inqudium.legatium.common.MdcKeys
import eu.inqudium.legatium.common.MdcScope
import eu.inqudium.legatium.common.NanoTimeSource
import eu.inqudium.legatium.common.Traceparent
import eu.inqudium.legatium.common.reportQuietly
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser
import java.net.URI

/**
 * Logs ONE structured line per outbound HTTP exchange - method, target, status, duration, request id,
 * optionally selected headers and bounded bodies - for every call a `RestClient` or `RestTemplate`
 * makes through it, and carries the exchange's identity in the MDC while the wire call runs.
 *
 * The envoy's report: the service sends a request to a foreign party and records what came of it. The
 * interceptor is the one place both blocking clients route every call through
 * (`ClientHttpRequestInterceptor`), which makes it the outbound counterpart of the sibling project
 * limesium's servlet filter - and the log line is shaped to match: the same fail-open contract, the same
 * level/outcome decoupling, the same header sections and body tee, with the `adapter_*` field family
 * instead of `endpoint_*`.
 *
 * ## Where it sits in the interceptor chain
 *
 * The auto-configuration appends this interceptor through a LATE customizer, so it runs INSIDE the
 * interceptors of earlier customizers and of the builder's own configuration: it sees the request as it
 * goes on the wire - after an authentication interceptor added its header, once per attempt of a
 * retrying interceptor. Interceptors the host adds AFTER the customizers ran (directly on a builder it
 * obtained from Boot) run inside this one and are outside that guarantee. The traceparent header is
 * not affected by the order: the client observation injects it into the request BEFORE any interceptor
 * runs (pinned beside a real bridge by the tracing integration test).
 *
 * ## Emission point: response close
 *
 * The exchange event is emitted when the response is CLOSED (see [CapturingClientHttpResponse]) - after
 * the client read the body through its converters, which is when status, headers, body and duration are
 * final. A call that produces no response (connection refused, a timeout before the status line) emits
 * right away with `adapter_outcome=failure` or `timeout` and no status; the exception is rethrown
 * UNCHANGED - this interceptor adds visibility only, error semantics belong to the client.
 *
 * When the call throws, a short WARN breadcrumb is additionally logged on the module's OWN logger, so
 * the failure is visible with its cause the moment it happens (the full ERROR event follows in the same
 * breath here, but the breadcrumb keeps the two twins' log streams alike, and the exchange logger keeps
 * its one-event-per-exchange contract).
 *
 * ## MDC coverage
 *
 * The call scope covers the wire call: every log line written by inner interceptors, the request
 * factory or the HTTP engine carries `adapter_request_id`/`adapter_method`/`adapter_route`. It is an
 * ADDITIVE overlay: an inbound request's `endpoint_*` identity (limesium) or a bridge's trace keys on
 * the thread stay in place, so the client line joins the server line by MDC alone. The body read and
 * the emission happen after the interceptor returned, under the emission's own scope.
 *
 * ## Fail-open, including the wiring
 *
 * The fail-open contract covers the WHOLE interceptor, not only the emission: a failure while wiring
 * the exchange (identity resolution against a host-provided bean, header selection, capture
 * construction) degrades this interceptor to a plain pass-through - counted as `stage=wiring` on the
 * fail-open meter - and the call proceeds unlogged but undisturbed.
 *
 * ## Manual wiring: interceptors on one `MeterRegistry` share one metrics owner
 *
 * The module's meters are identified by name, so all interceptors constructed against the same registry
 * share a single internal metrics owner: the counters and the `adapter.logging.exchanges.open` gauge
 * report totals ACROSS those interceptors, not per interceptor. The auto-configuration wires exactly
 * one interceptor per context, where the distinction never shows.
 */
class ClientRequestLoggingInterceptor
    @JvmOverloads
    constructor(
        private val properties: ClientLoggingProperties,
        private val nanoTime: NanoTimeSource,
        private val correlationIds: CorrelationIdGenerator,
        meterRegistry: MeterRegistry,
        /** How masked header values render; the auto-configuration passes the host's bean, [HeaderValueMasker.DEFAULT] otherwise. */
        private val masker: HeaderValueMasker = HeaderValueMasker.DEFAULT,
    ) : ClientHttpRequestInterceptor {
        private val metrics = ClientLoggingMetrics.forRegistry(meterRegistry)
        private val emitter = ExchangeLogEmitter(properties, nanoTime, metrics, masker)

        // Parsed ONCE at construction: an invalid pattern is a configuration error and fails the context
        // start with the parser's message, instead of failing per call.
        private val includePathPatterns: List<PathPattern> =
            properties.includePathPatterns.map { PathPatternParser.defaultInstance.parse(it) }
        private val excludedHosts: Set<String> = properties.excludeHosts.map { it.lowercase() }.toSet()

        /**
         * The interceptor is active for a call when its host is not excluded, its path matches ANY include
         * pattern (empty includes = every call) and NO exclude prefix - an exclude always wins. Path
         * matching runs on the raw request path parsed into segments that DECODE for matching, the exclude
         * prefixes compare against the decoded path rebuilt from those segments (path parameters dropped) -
         * so a percent-encoded variant cannot slip past an exclude, identical in semantics with the
         * WebClient twin.
         */
        internal fun shouldNotFilter(uri: URI): Boolean {
            if (excludedHosts.isNotEmpty() && uri.host?.lowercase() in excludedHosts) {
                return true
            }
            // Nothing configured to match (the shipped default): active for every call, so the answer
            // needs no PathContainer.
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

        override fun intercept(
            request: HttpRequest,
            body: ByteArray,
            execution: ClientHttpRequestExecution,
        ): ClientHttpResponse {
            if (shouldNotFilter(request.uri)) {
                return execution.execute(request, body)
            }
            // The WIRING is fail-open too, not only the emission: identity resolution and the time source
            // are host-provided beans, and header selection touches the request's header map - an exception
            // in any of them must degrade this interceptor to a plain pass-through, never fail the call.
            val exchange: Exchange? =
                try {
                    wireExchange(request, body)
                } catch (e: Exception) {
                    reportQuietly {
                        metrics.wiringFailure()
                        internalLog.error(
                            "Client logging could not be wired for {} {} - continuing without logging: {}",
                            request.method,
                            request.uri,
                            e.toString(),
                            e,
                        )
                    }
                    null
                }
            if (exchange == null) {
                return execution.execute(request, body)
            }
            // The call-wide MDC scope is logging-owned work and therefore fail-open too: a throwing MDC
            // adapter degrades the identity feature, never the call. MdcScope itself rolls back a partial
            // install before rethrowing, so the calling thread never keeps half an identity.
            val mdcScope: MdcScope? =
                try {
                    MdcScope(exchange.requestId, exchange.method, exchange.target)
                } catch (e: Exception) {
                    reportQuietly {
                        metrics.wiringFailure()
                        internalLog.error(
                            "MDC scope could not be opened for {} {} - continuing without call MDC: {}",
                            exchange.method,
                            exchange.target,
                            e.toString(),
                            e,
                        )
                    }
                    null
                }
            // The optional arrival line, before the call and OUTSIDE the try below: a failure in it must be
            // confined (it is, see the emitter - including the level gate), never misattributed as a call
            // failure.
            if (properties.logRequestStart) {
                emitter.logRequestStart(exchange)
            }
            try {
                val response = execution.execute(request, body)
                exchange.response = response
                // Pure object construction, no host call - nothing here can fail and strand the response.
                return CapturingClientHttpResponse(
                    delegate = response,
                    capture = exchange.responseCapture,
                    onReadFailure = { e -> exchange.failure = e },
                    onClose = { completeExchange(exchange) },
                )
            } catch (e: Exception) {
                // No response: the exchange ends here. Breadcrumb first (a host-backend call, guarded so a
                // throwing backend cannot REPLACE the client's exception), then the event, then the
                // unchanged rethrow - IOException for the client to map, or whatever else the engine threw.
                exchange.failure = e
                reportQuietly {
                    internalLog.warn(
                        "Client http exchange failed: {} {} - {} [{}={}]",
                        exchange.method,
                        exchange.target,
                        e.toString(),
                        MdcKeys.REQUEST_ID,
                        exchange.requestId,
                    )
                }
                completeExchange(exchange)
                throw e
            } finally {
                // Restoration is guarded separately: a throwing MDC adapter here must neither fail the call
                // nor MASK an exception already propagating out of it - it costs the restoration, counted
                // as stage=wiring.
                try {
                    mdcScope?.close()
                } catch (e: Exception) {
                    reportQuietly {
                        metrics.wiringFailure()
                        internalLog.warn(
                            "MDC restoration failed for {} {} - the calling thread may carry stale client keys: {}",
                            exchange.method,
                            exchange.target,
                            e.toString(),
                            e,
                        )
                    }
                }
            }
        }

        /**
         * Everything that must exist before the wire call runs: identity resolution and the traceless
         * correlation header, the captures, the eagerly captured request-side coordinates and the gauge.
         * Called exclusively from the fail-open block in [intercept] - anything thrown here is confined
         * there and degrades the interceptor to a pass-through.
         */
        private fun wireExchange(
            request: HttpRequest,
            body: ByteArray,
        ): Exchange {
            val headers = request.headers
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
            if (trace == null && headerCorrelationId == null) {
                headers.set(properties.correlationIdHeader, requestId)
            }

            // The request body is what the client hands the interceptor: complete, in memory, already
            // final. A capture exists when the body is logged in ANY mode OR measured - `on-failure` needs
            // the bytes before the outcome is known and the emitter drops them on success; measure-only
            // runs the capture in count-only mode (limit 0: nothing buffered, every byte counted).
            val requestCapture =
                if (properties.logRequestBody.captures || properties.measureRequestBodySize) {
                    BoundedBodyCapture(if (properties.logRequestBody.captures) properties.maxBodyBytes else 0).also {
                        it.capture(body, 0, body.size)
                    }
                } else {
                    null
                }
            val responseCapture =
                if (properties.logResponseBody.captures || properties.measureResponseBodySize) {
                    BoundedBodyCapture(if (properties.logResponseBody.captures) properties.maxBodyBytes else 0)
                } else {
                    null
                }

            // RAW (still percent-encoded) path and query, as they go on the wire - twin parity with the
            // WebClient module, and the log-injection guard: java.net.URI's decoded getPath()/getQuery()
            // turn `%0A`/`%0D` into real line breaks that would forge lines in every plain-text sink
            // (message, MDC adapter_route, fields). Activation matching keeps the decoded path (the
            // representation a server router would match).
            val uri = request.uri
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
                    method = request.method.name(),
                    target = target,
                    host = host,
                    path = path,
                    query = if (properties.includeQueryString) uri.rawQuery else null,
                    requestId = requestId,
                    // Multi-value resolution, natively from HttpHeaders - AFTER the correlation header was
                    // added, so a selected correlation header shows what actually went out.
                    requestHeaders =
                        properties.requestHeaders.select(headers.headerNames(), masker) { name ->
                            headers[name]?.takeIf { it.isNotEmpty() }?.joinToString(", ")
                        },
                    uriTemplate = request.attributes[URI_TEMPLATE_ATTRIBUTE] as? String,
                    requestCapture = requestCapture,
                    responseCapture = responseCapture,
                    requestCharset = headers.declaredCharsetOrUtf8(),
                    startNanos = nanoTime.nanoTime(),
                    traceId = trace?.first,
                    spanId = trace?.second,
                )
            metrics.exchangeOpened()
            return exchange
        }

        /**
         * The exactly-once end of an exchange - gauge close plus emission - guarded by [Exchange.completed]:
         * the response close (possibly twice) and the no-response failure path all arrive here; whichever
         * wins the CAS completes, the rest are no-ops.
         */
        private fun completeExchange(exchange: Exchange) {
            if (!exchange.completed.compareAndSet(false, true)) {
                return
            }
            metrics.exchangeCompleted()
            emitter.logExchange(exchange)
        }

        companion object {
            /**
             * Request attribute under which `RestClient` records the URI template of a call made through the
             * template form of `uri(...)`. Mirrors `DefaultRestClient.URI_TEMPLATE_ATTRIBUTE`
             * (`RestClient.class.getName() + ".uriTemplate"`), which is package-private - derived the same
             * way instead, so it matches the value the client sets (pinned by `UriTemplateAttributeTest`)
             * and stays null for an expanded `URI` and for `RestTemplate`, which records no such attribute.
             */
            const val URI_TEMPLATE_ATTRIBUTE = "org.springframework.web.client.RestClient.uriTemplate"

            // The breadcrumb and wiring failures go to the module's own logger, never onto the exchange
            // logger - the exchange log stream stays parseable.
            private val internalLog = LoggerFactory.getLogger(ClientRequestLoggingInterceptor::class.java)
        }
    }
