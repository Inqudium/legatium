package eu.inqudium.legatium.webclient.logging

import eu.inqudium.legatium.common.Classification
import eu.inqudium.legatium.common.ClientLogField
import eu.inqudium.legatium.common.ClientLoggingMetrics
import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.ClientOutcome
import eu.inqudium.legatium.common.HeaderValueMasker
import eu.inqudium.legatium.common.MdcKeys
import eu.inqudium.legatium.common.MdcScope
import eu.inqudium.legatium.common.NanoTimeSource
import eu.inqudium.legatium.common.Timeouts
import eu.inqudium.legatium.common.TraceMdcKeys
import eu.inqudium.legatium.common.addKeyValue
import eu.inqudium.legatium.common.addKeyValueIfPresent
import eu.inqudium.legatium.common.declaredCharsetOrUtf8
import eu.inqudium.legatium.common.failOpen
import eu.inqudium.legatium.common.reportQuietly
import eu.inqudium.legatium.common.setCauseIfPresent
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.http.HttpHeaders
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
                        "Adapter http exchange started ${exchange.method} ${exchange.target} " +
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
        // Compared at full precision (a 1.5 ms threshold must not flag a 1 ms exchange); the 1 ms floor
        // is [ClientLoggingProperties.slowRequestThreshold]'s.
        val slow = Duration.ofNanos(elapsedNanos) >= properties.slowRequestThreshold
        // Metrics BEFORE the level gate: a metric must not depend on how loud the logger is configured.
        recordBodySizesQuietly(exchange)
        // A cancelled or failed call may never have received a response - status then stays null, the
        // message shows "-" and the status field is omitted rather than invented.
        val response = exchange.response
        val status = response?.statusCode()?.value()
        val classification = classify(exchange.failure, exchange.cancelled, status)
        // Slow escalates INFO -> WARN without changing the outcome.
        val level = if (slow && classification.level == Level.INFO) Level.WARN else classification.level
        if (!exchangeLog.isEnabledForLevel(level)) {
            return
        }
        // The emission scope OWNS the trace keys ([MdcScope]) - identical to the RestClient twin; `use`
        // records a close-time failure as suppressed instead of masking an emission failure.
        MdcScope(exchange.requestId, exchange.method, exchange.target, exchange.traceId, exchange.spanId, ownsTraceKeys = true).use {
            logEvent(exchange, classification, level, status, elapsedNanos / NANOS_PER_MS, slow, response?.headers()?.asHttpHeaders())
        }
    }

    /**
     * Severity and semantic decoupled, exactly like the RestClient twin - with `cancelled` on top: a
     * timeout in the error's cause chain is WARN with its own outcome, any other error signal is ERROR,
     * a subscription the caller abandoned (a downstream timeout operator, a disposed caller) is WARN, a
     * 5xx answer without an error signal is WARN (the peer answered).
     */
    private fun classify(
        failure: Throwable?,
        cancelled: Boolean,
        status: Int?,
    ): Classification =
        when {
            failure != null && Timeouts.isTimeout(failure) -> Classification(Level.WARN, ClientOutcome.TIMEOUT, failure)
            failure != null -> Classification(Level.ERROR, ClientOutcome.FAILURE, failure)
            cancelled -> Classification(Level.WARN, ClientOutcome.CANCELLED, null)
            (status ?: 0) >= 500 -> Classification(Level.WARN, ClientOutcome.FAILURE, null)
            else -> Classification(Level.INFO, ClientOutcome.SUCCESS, null)
        }

    /** The one immutable builder chain of the completion event - identical to the RestClient twin; optional fields are left off by the *IfPresent helpers. */
    private fun logEvent(
        exchange: Exchange,
        classification: Classification,
        level: Level,
        status: Int?,
        durationMs: Long,
        slow: Boolean,
        headers: HttpHeaders?,
    ) {
        val (requestBody, responseBody) = loggedBodies(exchange, classification.outcome, status, headers)
        val traceSuffix =
            if (exchange.traceId != null || exchange.spanId != null) {
                " ${TraceMdcKeys.TRACE_ID}=${exchange.traceId ?: "-"} ${TraceMdcKeys.SPAN_ID}=${exchange.spanId ?: "-"}"
            } else {
                ""
            }
        exchangeLog
            .atLevel(level)
            .setMessage(
                "Adapter http exchange ${exchange.method} ${exchange.target} -> ${status ?: "-"} " +
                    "[${MdcKeys.REQUEST_ID}=${exchange.requestId}$traceSuffix]",
            ).addKeyValue(ClientLogField.OUTCOME, classification.outcome.tagValue)
            .addKeyValue(ClientLogField.DURATION_MS, durationMs)
            .addKeyValue(ClientLogField.REQUEST_METHOD, exchange.method)
            .addKeyValueIfPresent(ClientLogField.RESPONSE_STATUS_CODE, status)
            .addKeyValueIfPresent(ClientLogField.URL_HOST, exchange.host)
            .addKeyValue(ClientLogField.URL_PATH, exchange.path)
            .setCauseIfPresent(classification.cause)
            .addKeyValueIfPresent(ClientLogField.SLOW, true.takeIf { slow })
            .addKeyValueIfPresent(ClientLogField.URL_TEMPLATE, exchange.uriTemplate)
            .addKeyValueIfPresent(ClientLogField.URL_QUERY, exchange.query)
            .addKeyValueIfPresent(ClientLogField.REQUEST_HEADERS, renderHeaders(exchange.requestHeaders))
            .addKeyValueIfPresent(ClientLogField.RESPONSE_HEADERS, renderHeaders(selectedResponseHeaders(headers)))
            .addKeyValueIfPresent(ClientLogField.REQUEST_BODY, requestBody)
            .addKeyValueIfPresent(ClientLogField.RESPONSE_BODY, responseBody)
            .log()
        // Guarded in [ClientLoggingMetrics.eventEmitted]: the event is already on the logger.
        metrics.eventEmitted(classification.outcome)
    }

    /** Multi-value resolution, natively from the reactive HttpHeaders. */
    private fun selectedResponseHeaders(headers: HttpHeaders?): List<Pair<String, String>> =
        headers?.let {
            properties.responseHeaders.select(it.headerNames(), masker) { name ->
                it[name]?.takeIf { values -> values.isNotEmpty() }?.joinToString(", ")
            }
        } ?: emptyList()

    /**
     * Body fields only when the direction's [eu.inqudium.legatium.common.BodyLogMode] admits THIS outcome ("failed" = outcome not
     * `success`, or a 4xx); a count-only capture (size metrics) must not surface as an empty field.
     */
    private fun loggedBodies(
        exchange: Exchange,
        outcome: ClientOutcome,
        status: Int?,
        headers: HttpHeaders?,
    ): Pair<String?, String?> {
        val failed = outcome != ClientOutcome.SUCCESS || (status ?: 0) in 400..499
        val requestBody = if (properties.logRequestBody.logs(failed)) exchange.requestCapture?.loggedValue(exchange.requestCharset) else null
        val responseBody =
            if (properties.logResponseBody.logs(failed)) {
                exchange.responseCapture?.loggedValue(headers?.declaredCharsetOrUtf8() ?: StandardCharsets.UTF_8)
            } else {
                null
            }
        return requestBody to responseBody
    }

    /** Guarded on its own: a host registry that rejects the body-size summary (meter-id conflict) costs the sample, never the event. */
    private fun recordBodySizesQuietly(exchange: Exchange) {
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

        // The module's own logger, never the exchange logger: the exchange log stream stays parseable.
        private val internalLog = LoggerFactory.getLogger(ExchangeLogEmitter::class.java)
    }
}
