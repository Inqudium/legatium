package eu.inqudium.legatium.webclient.logging

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
 * IDENTICAL message and field format of the legatium-restclient-logging emitter (fields locked by
 * `ClientLogFieldTest`, message text by `TwinContractTest`, in both twins); only the disposition
 * vocabulary is wider where the stack is (`cancelled`, which a blocking call cannot be).
 *
 * ## Levels
 *
 * The level carries severity only, `adapter_outcome` the semantic: ERROR when the call errored (no
 * response, or the body errored), WARN for a timeout, a cancellation, a 5xx answer, or an exchange that
 * reached [ClientLoggingProperties.slowRequestThreshold], INFO otherwise. Severity and outcome are
 * resolved BEFORE the event is built, so a disabled level costs no assembly.
 *
 * ## Fail-open
 *
 * The guard covers everything after the exactly-once CAS: a failure inside the emission is reported on
 * this class's own logger, counted on the [ClientLoggingMetrics] fail-open counter, and an interrupt is
 * re-raised as a flag. Calls are never affected.
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
     * call, identical in format to the RestClient twin - INCLUDING the MDC: this emission opens the same
     * [MdcScope] (with the traceparent-derived trace overlay, owned) around the single log statement for
     * output parity.
     */
    fun logRequestStart(exchange: Exchange) {
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
     * The single emission point, called exactly once per exchange from `ClientRequestLoggingFilter.complete`
     * - the one place that wins the [Exchange.state] transition to `COMPLETED` (the response body's
     * terminal signal, or the response `Mono`'s error/cancel signal when no response arrived).
     */
    fun logExchange(exchange: Exchange) {
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
        // Freeze FIRST: from here on a late body chunk (an onNext still in flight after a cancellation)
        // can no longer move the captures - body text and size sample are one consistent snapshot.
        exchange.requestCapture?.freeze()
        exchange.responseCapture?.freeze()
        val elapsedNanos = nanoTime.nanoTime() - exchange.startNanos
        val durationMs = elapsedNanos / NANOS_PER_MS
        val failure = exchange.failure
        val cancelled = exchange.cancelled
        val response = exchange.response
        // A cancelled or failed call may never have received a response - status then stays null, the
        // message shows "-" and the status field is omitted rather than invented.
        val status: Int? = response?.statusCode()?.value()
        // Compared at full precision and overflow-free (Duration comparison, no toMillis/toNanos
        // truncation): a 1.5 ms threshold must not flag a 1 ms exchange. The logged duration field keeps
        // its millisecond resolution, which is why the properties reject thresholds below 1 ms.
        val slow = Duration.ofNanos(elapsedNanos) >= properties.slowRequestThreshold
        // Metrics BEFORE the level gate: a metric must not depend on how loud the logger is configured.
        // Guarded on their own: a host registry that rejects the body-size summary (meter-id conflict)
        // costs the sample, never the event.
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
        // Severity and semantic decoupled, exactly like the RestClient twin - with `cancelled` on top: a
        // timeout in the error's cause chain is WARN with its own outcome, any other error signal is
        // ERROR, a cancelled subscription (a downstream timeout operator, a `take`, a disposed caller)
        // is WARN, a 5xx answer without an error signal is WARN (the peer answered); slow escalates
        // INFO -> WARN without changing the outcome.
        val (baseLevel, outcome) =
            when {
                failure != null && Timeouts.isTimeout(failure) -> Level.WARN to ClientLoggingMetrics.OUTCOME_TIMEOUT
                failure != null -> Level.ERROR to ClientLoggingMetrics.OUTCOME_FAILURE
                cancelled -> Level.WARN to ClientLoggingMetrics.OUTCOME_CANCELLED
                (status ?: 0) >= 500 -> Level.WARN to ClientLoggingMetrics.OUTCOME_FAILURE
                else -> Level.INFO to ClientLoggingMetrics.OUTCOME_SUCCESS
            }
        val level = if (slow && baseLevel == Level.INFO) Level.WARN else baseLevel
        if (!exchangeLog.isEnabledForLevel(level)) {
            return
        }
        // The emission scope carries the exchange identity and the traceparent-derived trace context into
        // the MDC (owned: an unparsed id is removed, so a stale bridge id on the completing thread cannot
        // join the event to a foreign trace), so a structured encoder emits them as fields; the message
        // repeats the gist inline for plain-text appenders - identical to the RestClient twin. `use`
        // restores the scope and records a close-time failure as suppressed instead of masking an
        // emission failure (both land in logExchange's guard either way).
        val traceSuffix =
            if (exchange.traceId != null || exchange.spanId != null) {
                " ${TraceMdcKeys.TRACE_ID}=${exchange.traceId ?: "-"} ${TraceMdcKeys.SPAN_ID}=${exchange.spanId ?: "-"}"
            } else {
                ""
            }
        MdcScope(exchange.requestId, exchange.method, exchange.target, exchange.traceId, exchange.spanId, ownsTraceKeys = true).use {
            // Multi-value resolution, natively from the reactive HttpHeaders.
            val responseHeaders =
                response?.headers()?.asHttpHeaders()?.let { headers ->
                    properties.responseHeaders.select(headers.headerNames(), masker) { name ->
                        headers[name]?.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    }
                } ?: emptyList()
            // Body fields only when the direction's mode admits THIS outcome: `on-failure` captured the
            // bytes (the outcome is unknown while they flow) and discards them here for a clean
            // exchange (success outcome, no 4xx). A capture may also exist in count-only mode for the size metrics, and its empty buffer
            // must not surface as a truncated-looking field.
            val failed = outcome != ClientLoggingMetrics.OUTCOME_SUCCESS || (status ?: 0) in 400..499
            val requestBody =
                if (properties.logRequestBody.logs(failed)) exchange.requestCapture?.loggedValue(exchange.requestCharset) else null
            val responseBody =
                if (properties.logResponseBody.logs(failed)) {
                    exchange.responseCapture?.loggedValue(
                        response?.headers()?.asHttpHeaders()?.declaredCharsetOrUtf8() ?: StandardCharsets.UTF_8,
                    )
                } else {
                    null
                }
            // One immutable builder chain; optional fields are left off by the *IfPresent helpers -
            // identical to the RestClient twin.
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
                .setCauseIfPresent(failure)
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
