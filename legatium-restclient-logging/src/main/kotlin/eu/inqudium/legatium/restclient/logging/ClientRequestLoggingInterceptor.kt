package eu.inqudium.legatium.restclient.logging

import eu.inqudium.legatium.common.AdapterName
import eu.inqudium.legatium.common.BodyLogMode
import eu.inqudium.legatium.common.ClientActivation
import eu.inqudium.legatium.common.ClientIdentity
import eu.inqudium.legatium.common.ClientLoggingMetrics
import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.ClientStack
import eu.inqudium.legatium.common.CorrelationIdGenerator
import eu.inqudium.legatium.common.HeaderValueMasker
import eu.inqudium.legatium.common.MdcKeys
import eu.inqudium.legatium.common.MdcScope
import eu.inqudium.legatium.common.NanoTimeSource
import eu.inqudium.legatium.common.RequestTarget
import eu.inqudium.legatium.common.declaredCharsetOrUtf8
import eu.inqudium.legatium.common.reportQuietly
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse

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
 * the failure is visible with its cause the moment it happens, while the exchange logger keeps its
 * one-event-per-exchange contract (the full ERROR event follows in the same breath). This is a
 * deliberate difference from the reactive twin, which reports a failed call through the exchange event
 * alone.
 *
 * ## MDC coverage
 *
 * The call scope covers the wire call: every log line written by inner interceptors, the request
 * factory or the HTTP engine carries `adapter_request_id`/`adapter_method`/`adapter_route`. It is an
 * ADDITIVE overlay: an inbound request's `endpoint_*` identity (limesium) or a bridge's trace keys on
 * the thread stay in place, so the client line joins the server line by MDC alone. The body read and
 * the emission happen after the interceptor returned, under the emission's own scope - on the caller's
 * thread in the usual case. A host that hands the response to ANOTHER thread and closes it there
 * still gets the join: the caller's MDC is snapshotted at wiring (own and trace keys excluded) and
 * restored around the emission on that other thread, additively (ADR-0011).
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
        /** The bound `adapter-logging.*` configuration; also decides the default [masker]. */
        private val properties: ClientLoggingProperties,
        /** Monotonic time for `adapter_duration_ms`; tests pin it, production passes [NanoTimeSource.SYSTEM]. */
        private val nanoTime: NanoTimeSource,
        /** Supplies the id a TRACELESS call sends (ADR-0002); production passes [CorrelationIdGenerator.DEFAULT]. */
        private val correlationIds: CorrelationIdGenerator,
        /**
         * The host's registry the meters are consumed from; interceptors on one registry share one
         * metrics owner (section "Manual wiring" of the class KDoc).
         */
        meterRegistry: MeterRegistry,
        /**
         * How masked header values render. Defaults to the masker the properties' `masking-key` selects
         * ([HeaderValueMasker.forKey]) - so a manually constructed interceptor honours a configured key
         * exactly like the auto-configured one; the auto-configuration passes the host's bean instead.
         */
        private val masker: HeaderValueMasker = HeaderValueMasker.forKey(properties.maskingKey),
    ) : ClientHttpRequestInterceptor {
        private val metrics = ClientLoggingMetrics.forRegistry(meterRegistry, ClientStack.RESTCLIENT)

        /** Exposed for the tests, which swap the emitter's caller-MDC restorer to drive its fail-open path. */
        internal val emitter = ExchangeLogEmitter(properties, nanoTime, metrics, masker)

        // Activation is the shared implementation (ADR-0003): identical semantics on both stacks by construction.
        private val activation = ClientActivation(properties)

        override fun intercept(
            request: HttpRequest,
            body: ByteArray,
            execution: ClientHttpRequestExecution,
        ): ClientHttpResponse {
            if (activation.shouldNotFilter(request.uri)) {
                return execution.execute(request, body)
            }
            val exchange = wireOrNull(request, body) ?: return execution.execute(request, body)
            // From here the gauge counts this exchange: everything that can still throw - the call scope,
            // the arrival line, the wire call - runs INSIDE the try, so the catch (Throwable) below and the
            // finally own the gauge and the scope whatever escapes.
            var callScope: MdcScope? = null
            try {
                // The call scope confines an Exception itself (`openCallScope`); an Error from the host's
                // MDC adapter escapes it and takes the same way as one from the arrival line or the wire
                // call: gauge closed, nothing to restore.
                callScope = openCallScope(exchange)
                // The optional arrival line, before the call but INSIDE the try: an Exception in it is
                // confined in `ExchangeLogEmitter.logRequestStart` (level gate included) and can never
                // reach the catch below as a call failure - only an Error can escape, and it then takes
                // the same way as one from the wire call: gauge closed, call scope restored.
                if (properties.logRequestStart) {
                    emitter.logRequestStart(exchange)
                }
                val response = execution.execute(request, body)
                snapshotResponse(exchange, response)
                // Pure object construction, no host call - nothing here can fail and strand the response.
                return CapturingClientHttpResponse(
                    delegate = response,
                    capture = exchange.responseCapture,
                    onFailure = { e -> exchange.failure = e },
                    onClose = { complete(exchange) },
                )
            } catch (e: Exception) {
                // No response: the exchange ends here. Breadcrumb first, then the event, then the
                // unchanged rethrow - IOException for the client to map, or whatever else the engine threw.
                exchange.failure = e
                breadcrumb(exchange, e)
                complete(exchange)
                throw e
            } catch (t: Throwable) {
                // An Error (LinkageError, VirtualMachineError, AssertionError from an inner interceptor,
                // a logging backend dying under the arrival line) is outside the fail-open promise
                // (`failOpen`) - but not outside the gauge: the exchange is abandoned, the liveness
                // signal stays truthful, no emission is attempted.
                abandonExchange(exchange, t)
                throw t
            } finally {
                closeCallScope(callScope, exchange)
            }
        }

        /**
         * Fail-open like the emission (section "Fail-open, including the wiring" of the class KDoc):
         * null degrades this interceptor to a plain pass-through, counted `stage=wiring`, never a failed call.
         */
        private fun wireOrNull(
            request: HttpRequest,
            body: ByteArray,
        ): Exchange? =
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

        /**
         * The call-wide MDC scope is logging-owned work and therefore fail-open too: a throwing MDC
         * adapter degrades the identity feature, never the call. [MdcScope] itself rolls back a partial
         * install before rethrowing, so the calling thread never keeps half an identity.
         */
        private fun openCallScope(exchange: Exchange): MdcScope? =
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

        /**
         * Restoration guarded separately - the teardown rule of [reportQuietly]: a throwing MDC adapter
         * here must neither fail the call nor MASK an exception already propagating out of it; it costs
         * the restoration, counted `stage=wiring`.
         */
        private fun closeCallScope(
            scope: MdcScope?,
            exchange: Exchange,
        ) {
            try {
                scope?.close()
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

        /**
         * Status and headers are final at handover and the engine can still answer; read them now - the
         * status as a value, the headers as the response's own header object - so the emission after the
         * client's close never asks a closed response for them. A refusing engine costs the status
         * (`-> -`), counted as wiring, never the event - and the CLIENT's own later access to the status
         * propagates through the wrapper, which then records the failure on the exchange.
         *
         * Status and headers also tell the response capture the body length the response carries
         * ([declaredBodyLength] into [BoundedBodyCapture.expectBytes]) - the second completion rule of
         * [CapturingClientHttpResponse].
         */
        private fun snapshotResponse(
            exchange: Exchange,
            response: ClientHttpResponse,
        ) {
            exchange.response = response
            try {
                val status = response.statusCode.value()
                exchange.responseStatus = status
                val headers = response.headers
                exchange.responseHeaders = headers
                exchange.responseCapture?.expectBytes(declaredBodyLength(exchange.method, status, headers))
            } catch (e: Exception) {
                reportQuietly {
                    metrics.wiringFailure()
                    internalLog.warn(
                        "Response status and headers could not be read for {} {} - the event will show no status: {}",
                        exchange.method,
                        exchange.target,
                        e.toString(),
                    )
                }
            }
        }

        /**
         * The body length the response carries and the engine will deliver unchanged: ZERO for the
         * answer to a HEAD request - whose `Content-Length` is the representation's, not the body's
         * (RFC 9110 §9.3.2), and which the clients never open - and for a 1xx, 204 or 304 answer -
         * Spring's own `IntrospectingClientHttpResponse.hasMessageBody()` rule, by which `RestClient`
         * and `RestTemplate` never open such a body, so the capture must not wait for an open that
         * never comes; `Content-Length` otherwise, when present and no `Content-Encoding`
         * other than `identity` is on the response; [BoundedBodyCapture.UNKNOWN_LENGTH] for the rest
         * (chunked, possibly decoded by the engine, or a value that is not a number). The header is
         * PEER-CONTROLLED input: it only ever feeds the completeness comparison - never an allocation or
         * a read - and a malformed value (Spring parses it with `Long.parseLong`) is folded to unknown
         * here rather than counted as a wiring failure.
         */
        private fun declaredBodyLength(
            method: String,
            status: Int,
            headers: HttpHeaders,
        ): Long {
            if (method == "HEAD" || status in 100..199 || status == 204 || status == 304) {
                return 0
            }
            val encoding = headers.getFirst(HttpHeaders.CONTENT_ENCODING)
            if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                return BoundedBodyCapture.UNKNOWN_LENGTH
            }
            val declared =
                try {
                    headers.contentLength
                } catch (e: NumberFormatException) {
                    return BoundedBodyCapture.UNKNOWN_LENGTH
                }
            return declared.takeIf { it >= 0 } ?: BoundedBodyCapture.UNKNOWN_LENGTH
        }

        /**
         * The WARN breadcrumb of a thrown call - a host-backend call, guarded so a throwing backend
         * cannot REPLACE the client's exception.
         */
        private fun breadcrumb(
            exchange: Exchange,
            e: Exception,
        ) {
            reportQuietly {
                internalLog.warn(
                    "Adapter http exchange failed: {} {} - {} [{}={}]",
                    exchange.method,
                    exchange.target,
                    e.toString(),
                    MdcKeys.REQUEST_ID,
                    exchange.requestId,
                )
            }
        }

        /**
         * Everything that must exist before the wire call runs: identity resolution and the traceless
         * correlation header, the captures, the eagerly captured request-side coordinates and the gauge.
         * Called exclusively from [wireOrNull] - anything thrown here is confined there.
         *
         * Ordered so that the steps WITHOUT side effects on the request or the meters - the coordinates,
         * the captures, the time source, the caller's MDC - run first: a host bean that throws there
         * (a time source, an MDC adapter) degrades the call to an unlogged pass-through with nothing
         * stamped on the wire and nothing counted. What remains after the header mutation is the header
         * selection, which must see the correlation header the peer will see; a host MASKER that throws
         * there still leaves the header on the request - the call proceeds, unlogged, with a correlation
         * id no line mentions - and is counted as wiring like every other wiring failure.
         */
        private fun wireExchange(
            request: HttpRequest,
            body: ByteArray,
        ): Exchange {
            val headers = request.headers
            // The header stamped on attempt 1 comes back on a re-entry by a retrying OUTER interceptor:
            // remembered on the request, so the origin counter keeps calling it `generated`.
            val identity = ClientIdentity.resolve(headers, properties, correlationIds, generatedEarlier = request.attributes[GENERATED_ID_ATTRIBUTE] as? String)
            val target = RequestTarget.of(request.uri)
            val requestCharset = headers.declaredCharsetOrUtf8()
            val startNanos = nanoTime.nanoTime()
            val callerMdc = captureCallerMdcQuietly(request)
            val captures = newCaptures()
            // The request body is what the client hands the interceptor: the complete serialized body,
            // in memory, BEFORE the wire call - what the client is about to send, not what reached the
            // peer (which is why the size sample waits for a response: `ClientLoggingProperties.measureRequestBodySize`).
            captures.request?.capture(body, 0, body.size)
            if (identity.sendCorrelationHeader) {
                headers.set(properties.correlationIdHeader, identity.requestId)
                request.attributes[GENERATED_ID_ATTRIBUTE] = identity.requestId
            }
            val exchange =
                Exchange(
                    method = request.method.name(),
                    target = target.target,
                    host = target.host,
                    path = target.path,
                    query = if (properties.includeQueryString) request.uri.rawQuery else null,
                    requestId = identity.requestId,
                    // Multi-value resolution, natively from HttpHeaders - AFTER the correlation header was
                    // added, so a selected correlation header shows what actually went out.
                    requestHeaders =
                        properties.requestHeaders.select(headers.headerNames(), masker) { name ->
                            headers[name]?.takeIf { it.isNotEmpty() }?.joinToString(", ")
                        },
                    uriTemplate = request.attributes[URI_TEMPLATE_ATTRIBUTE] as? String,
                    name = AdapterName.of(request.attributes[ADAPTER_NAME_ATTRIBUTE]),
                    requestCapture = captures.request,
                    responseCapture = captures.response,
                    requestCharset = requestCharset,
                    startNanos = startNanos,
                    traceId = identity.traceId,
                    spanId = identity.spanId,
                    callerMdc = callerMdc,
                )
            // The origin count LAST, right before the gauge: a wiring that fails above leaves the
            // correlation sum equal to the sum of exchanges that were actually opened. Guarded in
            // `ClientLoggingMetrics.requestId`: a throwing host counter never fails the call.
            metrics.requestId(identity.source)
            metrics.exchangeOpened()
            return exchange
        }

        /**
         * The caller's MDC for a close on another thread (ADR-0011), or nothing: a throwing MDC adapter
         * costs the snapshot, counted as stage=wiring, never the wiring - the call is logged either way,
         * with the module's own identity.
         */
        private fun captureCallerMdcQuietly(request: HttpRequest): CallerMdcSnapshot =
            try {
                CallerMdcSnapshot.capture()
            } catch (e: Exception) {
                reportQuietly {
                    metrics.wiringFailure()
                    internalLog.warn(
                        "The caller's MDC could not be captured for {} {} - a close on another thread logs without it: {}",
                        request.method,
                        request.uri,
                        e.toString(),
                    )
                }
                CallerMdcSnapshot.NONE
            }

        /**
         * A capture exists when the body is logged in ANY mode ([BodyLogMode.captures]) OR measured
         * ([ClientLoggingProperties.measureRequestBodySize]; then count-only, limit 0).
         */
        private fun newCaptures(): Captures =
            Captures(
                request = captureFor(properties.logRequestBody, properties.measureRequestBodySize),
                response = captureFor(properties.logResponseBody, properties.measureResponseBodySize),
            )

        /**
         * One side's capture by the rule of [newCaptures]: buffering up to the limit when logged,
         * count-only when merely measured, none otherwise.
         */
        private fun captureFor(
            mode: BodyLogMode,
            measured: Boolean,
        ): BoundedBodyCapture? =
            when {
                mode.captures -> BoundedBodyCapture(properties.maxBodyBytes)
                measured -> BoundedBodyCapture(0)
                else -> null
            }

        private class Captures(
            val request: BoundedBodyCapture?,
            val response: BoundedBodyCapture?,
        )

        /**
         * The exactly-once end of an exchange - gauge close plus emission - guarded by [Exchange.completed]:
         * the response close (possibly twice) and the no-response failure path all arrive here; whichever
         * wins the CAS completes, the rest are no-ops.
         */
        private fun complete(exchange: Exchange) {
            if (!exchange.completed.compareAndSet(false, true)) {
                return
            }
            metrics.exchangeCompleted()
            emitter.logExchange(exchange)
        }

        /**
         * The exactly-once end of an exchange the wire call left with an [Error]: the gauge closes, no
         * event is attempted (the logging backend is the likeliest source of such an Error), one WARN
         * breadcrumb on the module's logger, quietly.
         */
        private fun abandonExchange(
            exchange: Exchange,
            error: Throwable,
        ) {
            if (!exchange.completed.compareAndSet(false, true)) {
                return
            }
            reportQuietly {
                metrics.exchangeCompleted()
                internalLog.warn(
                    "Adapter http exchange abandoned: {} {} - {} [{}={}]",
                    exchange.method,
                    exchange.target,
                    error.toString(),
                    MdcKeys.REQUEST_ID,
                    exchange.requestId,
                )
            }
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

            /**
             * Request attribute remembering the correlation id this module generated and sent, for
             * re-entries by a retrying outer interceptor.
             */
            const val GENERATED_ID_ATTRIBUTE = "eu.inqudium.legatium.restclient.logging.generatedCorrelationId"

            /**
             * Request attribute the host sets to NAME a client - the value of `adapter_name` and the
             * `name` tag of the body meters (ADR-0009). Set once per client, and every call of that client
             * carries it:
             *
             * ```kotlin
             * RestClient.builder().defaultRequest { it.attribute(ADAPTER_NAME_ATTRIBUTE, "billing") }
             * ```
             *
             * A `RestTemplate` has no `defaultRequest`; an interceptor of the host's own that runs BEFORE
             * this one sets `request.attributes[ADAPTER_NAME_ATTRIBUTE]` instead. The same string on both
             * twins, so a host carrying both jars names its clients with one constant. A blank or non-string
             * value counts as no name.
             */
            const val ADAPTER_NAME_ATTRIBUTE = AdapterName.ATTRIBUTE

            // The module's own logger, never the exchange logger: the exchange log stream stays parseable.
            private val internalLog = LoggerFactory.getLogger(ClientRequestLoggingInterceptor::class.java)
        }
    }
