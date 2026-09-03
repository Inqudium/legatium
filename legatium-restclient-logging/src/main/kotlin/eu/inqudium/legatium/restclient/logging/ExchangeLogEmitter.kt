package eu.inqudium.legatium.restclient.logging

import eu.inqudium.legatium.common.ClientLogField
import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.HeaderValueMasker
import eu.inqudium.legatium.common.MdcKeys
import eu.inqudium.legatium.common.MdcScope
import eu.inqudium.legatium.common.NanoTimeSource
import eu.inqudium.legatium.common.Timeouts
import eu.inqudium.legatium.common.TraceMdcKeys
import eu.inqudium.legatium.common.addKeyValue
import eu.inqudium.legatium.common.addKeyValueIfPresent
import eu.inqudium.legatium.common.failOpen
import eu.inqudium.legatium.common.reportQuietly
import eu.inqudium.legatium.common.setCauseIfPresent
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.http.HttpHeaders
import org.springframework.http.InvalidMediaTypeException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Builds and emits the log events of an exchange - the arrival line and the completion event - with the
 * IDENTICAL message and field format of the legatium-webclient-logging emitter (fields locked by
 * `ClientLogFieldTest`, message text by `TwinContractTest`, in both twins). The interceptor owns the
 * client lifecycle and hands over a populated [Exchange]; this class owns the exchange logger, the
 * level/outcome decision, the field assembly, and the fail-open discipline around all of it.
 *
 * ## Levels
 *
 * The level carries severity only, `client_outcome` the semantic: ERROR when the call threw (no
 * response, or the body read failed), WARN for a timeout, a 5xx answer, or an exchange that reached
 * [ClientLoggingProperties.slowRequestThreshold], INFO otherwise. Severity and outcome are resolved
 * BEFORE the event is built, so an exchange whose level is disabled costs neither the key-value assembly
 * nor the header rendering.
 *
 * ## Fail-open
 *
 * A failure inside either emission is confined here: reported on this class's own logger, counted on the
 * [ClientLoggingMetrics] fail-open counter, and an interrupt is re-raised as a flag instead of being
 * consumed on the caller's thread. Calls are never affected.
 */
internal class ExchangeLogEmitter(
    private val properties: ClientLoggingProperties,
    private val nanoTime: NanoTimeSource,
    private val metrics: ClientLoggingMetrics,
    private val masker: HeaderValueMasker,
) {
    private val exchangeLog = LoggerFactory.getLogger(properties.loggerName)

    /**
     * The optional arrival line ([ClientLoggingProperties.logRequestStart]): what is known BEFORE the
     * wire call - method, target, query, selected request headers - at INFO on the exchange logger, under
     * the exchange's MDC with the traceparent-derived trace overlay: the scope OWNS the trace keys here
     * exactly as at emission, so the arrival line carries the same `traceId`/`spanId` pair as the
     * completion event. Deliberately WITHOUT `client_outcome`, status or duration: those exist only at
     * completion, and their absence is what keeps outcome-keyed dashboards blind to this extra line.
     */
    fun logRequestStart(exchange: Exchange) {
        // The guard covers the COMPLETE arrival operation including the level gate: isInfoEnabled is a
        // call into the host's logging backend and as fallible as the emission itself - outside the
        // guard it could fail the call this line merely announces.
        failOpen(
            onInterrupted = { e ->
                metrics.arrivalFailure()
                internalLog.debug("Interrupted while logging a request start; the line is dropped", e)
            },
            onFailure = { e ->
                metrics.arrivalFailure()
                internalLog.error(
                    "Exception while logging request start {} {}: {}",
                    exchange.method,
                    exchange.target,
                    e.toString(),
                    e,
                )
            },
        ) {
            if (!exchangeLog.isInfoEnabled) {
                return
            }
            MdcScope(exchange.requestId, exchange.method, exchange.target, exchange.traceId, exchange.spanId, ownsTraceKeys = true).use {
                exchangeLog
                    .atInfo()
                    .setMessage(
                        "Client http exchange started ${exchange.method} ${exchange.target} " +
                            "[${MdcKeys.REQUEST_ID}=${exchange.requestId}]",
                    ).addKeyValue(ClientLogField.REQUEST_METHOD, exchange.method)
                    .addKeyValueIfPresent(ClientLogField.URL_HOST, exchange.host)
                    .addKeyValue(ClientLogField.URL_PATH, exchange.path)
                    .addKeyValueIfPresent(ClientLogField.URL_TEMPLATE, exchange.uriTemplate)
                    .addKeyValueIfPresent(ClientLogField.URL_QUERY, exchange.query)
                    .addKeyValueIfPresent(ClientLogField.REQUEST_HEADERS, renderHeaders(exchange.requestHeaders))
                    .log()
            }
        }
    }

    /**
     * The single emission point of the completion event, called exclusively from the interceptor's
     * exactly-once completion - at response close, or right away when the call produced no response -
     * so status, response headers and captures are FINAL. The [Exchange.logged] guard backstops to
     * exactly-once.
     *
     * The emission runs under the exchange's MDC (an ADDITIVE overlay: an inbound request's identity or
     * a bridge's keys on the thread stay visible beside it), so the encoder emits the request id as an
     * MDC field rather than as a structured key-value; the message repeats method/target/status and the
     * request id inline, so a plain-text appender that drops key-values and MDC still shows the gist of
     * the exchange.
     */
    fun logExchange(exchange: Exchange) {
        if (!exchange.logged.compareAndSet(false, true)) {
            return
        }
        // The fail-open guard covers EVERYTHING after the exactly-once CAS: the pre-gate section reads
        // host-provided beans (the time source) and the response object - an exception there must not
        // escape into the client's close path and lose the event WITHOUT the emission counter seeing it.
        failOpen(
            onInterrupted = { e ->
                metrics.emissionFailure()
                internalLog.debug("Interrupted while logging an exchange; the event is dropped", e)
            },
            onFailure = { e ->
                metrics.emissionFailure()
                internalLog.error(
                    "Exception while logging exchange {} {}: {}",
                    exchange.method,
                    exchange.target,
                    e.toString(),
                    e,
                )
            },
        ) {
            emitExchange(exchange)
        }
    }

    private fun emitExchange(exchange: Exchange) {
        val elapsedNanos = nanoTime.nanoTime() - exchange.startNanos
        val durationMs = elapsedNanos / NANOS_PER_MS
        val failure = exchange.failure
        val response = exchange.response
        // A status read is a host call on the response; a response that cannot even say its status is
        // logged like one that never arrived (`-> -`), the event itself is never lost over it.
        val status: Int? =
            try {
                response?.statusCode?.value()
            } catch (e: Exception) {
                null
            }
        // Full-precision, overflow-free comparison (twin parity): a 1.5 ms threshold must not flag a 1 ms
        // exchange. The logged duration keeps millisecond resolution - hence the 1 ms floor in the properties.
        val slow = Duration.ofNanos(elapsedNanos) >= properties.slowRequestThreshold
        // Metrics BEFORE the level gate: a metric must not depend on how loud the logger is configured.
        // Guarded on their own: a host registry that rejects the body-size summary (meter-id conflict)
        // costs the sample, never the event (twin parity with the WebClient module).
        try {
            recordBodySizes(exchange)
        } catch (e: Exception) {
            reportQuietly {
                metrics.wiringFailure()
                internalLog.warn(
                    "Body size could not be recorded for {} {} - the event follows without it: {}",
                    exchange.method,
                    exchange.target,
                    e.toString(),
                )
            }
        }
        // The SLF4J level carries the severity, client_outcome the semantic - decoupled on purpose (see
        // ClientLogField.OUTCOME): a timeout is WARN with its own outcome (the peer is slow, not broken),
        // any other thrown call is ERROR, a 5xx answer without an exception is WARN (the peer answered,
        // the application decides what to make of it); all of the latter two carry "failure". A slow but
        // otherwise healthy exchange escalates INFO -> WARN without changing outcome.
        val classification =
            when {
                failure != null && Timeouts.isTimeout(failure) -> {
                    Classification(Level.WARN, ClientLoggingMetrics.OUTCOME_TIMEOUT, failure)
                }

                failure != null -> {
                    Classification(Level.ERROR, ClientLoggingMetrics.OUTCOME_FAILURE, failure)
                }

                (status ?: 0) >= 500 -> {
                    Classification(Level.WARN, ClientLoggingMetrics.OUTCOME_FAILURE, null)
                }

                else -> {
                    Classification(Level.INFO, ClientLoggingMetrics.OUTCOME_SUCCESS, null)
                }
            }
        val outcome = classification.outcome
        val cause = classification.cause
        val level = if (slow && classification.level == Level.INFO) Level.WARN else classification.level
        if (!exchangeLog.isEnabledForLevel(level)) {
            return
        }
        // The emission scope overlays the trace context parsed from the outgoing traceparent header
        // (ADR-0002), so the encoder emits the SAME traceId/spanId the request went out with. The ids
        // ride the MDC only, not the key-values; the message suffix below is the one extra, for
        // plain-text appenders that drop the MDC. The scope OWNS the trace keys: an id that was not
        // parsed is removed for the emission, so a stale bridge id on the closing thread cannot join the
        // event to a foreign trace.
        val mdcScope =
            MdcScope(exchange.requestId, exchange.method, exchange.target, exchange.traceId, exchange.spanId, ownsTraceKeys = true)
        val traceSuffix =
            if (exchange.traceId != null || exchange.spanId != null) {
                " ${TraceMdcKeys.TRACE_ID}=${exchange.traceId ?: "-"} ${TraceMdcKeys.SPAN_ID}=${exchange.spanId ?: "-"}"
            } else {
                ""
            }
        try {
            // Multi-value resolution, like the request side: a single-value getFirst would silently
            // truncate repeated headers (Set-Cookie being the classic).
            val responseHeaders =
                response?.headers?.let { headers ->
                    properties.responseHeaders.select(headers.headerNames(), masker) { name ->
                        headers[name]?.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    }
                } ?: emptyList()
            // Body fields only when the direction's mode admits THIS outcome: `on-failure` captured the
            // bytes (the outcome is unknown while they flow) and discards them here for a success. A
            // capture may also exist in count-only mode for the size metrics, and its empty buffer must
            // not surface as a truncated-looking field.
            val succeeded = outcome == ClientLoggingMetrics.OUTCOME_SUCCESS
            val requestBody =
                if (properties.logRequestBody.logs(succeeded)) exchange.requestCapture?.loggedValue(exchange.requestCharset) else null
            val responseBody =
                if (properties.logResponseBody.logs(succeeded)) {
                    exchange.responseCapture?.loggedValue(response?.headers?.declaredCharsetOrUtf8() ?: StandardCharsets.UTF_8)
                } else {
                    null
                }
            // One immutable builder chain; optional fields are left off by the *IfPresent helpers. Both
            // halves of the path pair: the expanded path (high cardinality, per-call) and the
            // low-cardinality URI template for grouping - only when the client recorded one. The host as
            // its own field (the outbound coordinate), the query as its own field (the path excludes it).
            exchangeLog
                .atLevel(level)
                .setMessage(
                    "Client http exchange ${exchange.method} ${exchange.target} -> ${status ?: "-"} " +
                        "[${MdcKeys.REQUEST_ID}=${exchange.requestId}$traceSuffix]",
                ).addKeyValue(ClientLogField.OUTCOME, outcome)
                .addKeyValue(ClientLogField.DURATION_MS, durationMs)
                .addKeyValue(ClientLogField.REQUEST_METHOD, exchange.method)
                .addKeyValueIfPresent(ClientLogField.RESPONSE_STATUS_CODE, status)
                .addKeyValueIfPresent(ClientLogField.URL_HOST, exchange.host)
                .addKeyValue(ClientLogField.URL_PATH, exchange.path)
                .setCauseIfPresent(cause)
                .addKeyValueIfPresent(ClientLogField.SLOW, true.takeIf { slow })
                .addKeyValueIfPresent(ClientLogField.URL_TEMPLATE, exchange.uriTemplate)
                .addKeyValueIfPresent(ClientLogField.URL_QUERY, exchange.query)
                .addKeyValueIfPresent(ClientLogField.REQUEST_HEADERS, renderHeaders(exchange.requestHeaders))
                .addKeyValueIfPresent(ClientLogField.RESPONSE_HEADERS, renderHeaders(responseHeaders))
                .addKeyValueIfPresent(ClientLogField.REQUEST_BODY, requestBody)
                .addKeyValueIfPresent(ClientLogField.RESPONSE_BODY, responseBody)
                .log()
            // Guarded inside the metrics: a throwing host counter after a successful log() must not be
            // reported as a lost emission.
            metrics.eventEmitted(outcome)
        } finally {
            mdcScope.close()
        }
    }

    /**
     * The opt-in body measurements: the size samples, and - for the response side - the read-state
     * counter, which is what tells an unread body from an absent one (the size sample cannot: both are
     * zero bytes and record nothing). The read state is recorded only when a response exists: a call
     * that never got an answer has no body to consume.
     */
    private fun recordBodySizes(exchange: Exchange) {
        if (properties.measureRequestBodySize) {
            exchange.requestCapture?.let { metrics.requestBodySize(exchange.uriTemplate, exchange.host, it.totalBytes) }
        }
        if (properties.measureResponseBodySize) {
            exchange.responseCapture?.let {
                metrics.responseBodySize(exchange.uriTemplate, exchange.host, it.totalBytes)
                if (exchange.response != null) {
                    metrics.responseBodyRead(exchange.uriTemplate, exchange.host, it.readState)
                }
            }
        }
    }

    /** Renders selected headers as `[name:"value", ...]`, or null when nothing was selected or present. */
    private fun renderHeaders(headers: List<Pair<String, String>>): String? {
        if (headers.isEmpty()) {
            return null
        }
        return headers.joinToString(separator = ", ", prefix = "[", postfix = "]") { (name, value) -> "$name:\"$value\"" }
    }

    /** The level/outcome/cause triple one exchange classifies to - the `when` above yields it as one value. */
    private class Classification(
        val level: Level,
        val outcome: String,
        val cause: Throwable?,
    )

    companion object {
        private const val NANOS_PER_MS = 1_000_000L

        // Failures of the logging itself go to the module's own logger, never onto the exchange logger -
        // the exchange log stream stays parseable.
        private val internalLog = LoggerFactory.getLogger(ExchangeLogEmitter::class.java)
    }
}

/**
 * The charset the `Content-Type` declares, UTF-8 when there is none or the media type does not parse -
 * a malformed header is the peer's (or the caller's) problem and must not cost the log line. One
 * definition for the request side (wiring) and the response side (emission).
 */
internal fun HttpHeaders.declaredCharsetOrUtf8(): Charset =
    try {
        contentType?.charset
    } catch (e: InvalidMediaTypeException) {
        null
    } ?: StandardCharsets.UTF_8
