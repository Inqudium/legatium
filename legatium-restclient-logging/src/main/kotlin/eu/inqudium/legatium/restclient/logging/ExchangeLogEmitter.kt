package eu.inqudium.legatium.restclient.logging

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
 * IDENTICAL message and field format of the legatium-webclient-logging emitter (fields locked by
 * `ClientLogFieldTest`, message text by `TwinContractTest`, in both twins). The interceptor owns the
 * client lifecycle and hands over a populated [Exchange]; this class owns the exchange logger, the
 * level/outcome decision, the field assembly, and the fail-open discipline around all of it.
 *
 * ## Levels
 *
 * The level carries severity only, `adapter_outcome` the semantic: ERROR when the call threw (no
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
     * completion event. Deliberately WITHOUT `adapter_outcome`, status or duration: those exist only at
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
     * The single emission point of the completion event, called exclusively from the interceptor's
     * exactly-once completion - at response close, or right away when the call produced no response -
     * so status, response headers and captures are FINAL; the interceptor's [Exchange.completed] CAS is
     * the one exactly-once guard.
     *
     * The emission runs under the exchange's MDC (an ADDITIVE overlay: an inbound request's identity or
     * a bridge's keys on the thread stay visible beside it), so the encoder emits the request id as an
     * MDC field rather than as a structured key-value; the message repeats method/target/status and the
     * request id inline, so a plain-text appender that drops key-values and MDC still shows the gist of
     * the exchange.
     */
    fun logExchange(exchange: Exchange) {
        // The fail-open guard covers EVERYTHING after the interceptor's exactly-once CAS: the pre-gate section reads
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
        // Compared at full precision (a 1.5 ms threshold must not flag a 1 ms exchange); the 1 ms floor
        // is [ClientLoggingProperties.slowRequestThreshold]'s.
        val slow = Duration.ofNanos(elapsedNanos) >= properties.slowRequestThreshold
        // Metrics BEFORE the level gate: a metric must not depend on how loud the logger is configured.
        recordBodySizesQuietly(exchange)
        // Status and headers were snapshotted at handover (the interceptor counted and warned if the
        // engine refused): the emission runs after the client's close and never asks the response again.
        val status = exchange.responseStatus
        val classification = classify(exchange.failure, status)
        // Slow escalates INFO -> WARN without changing the outcome.
        val level = if (slow && classification.level == Level.INFO) Level.WARN else classification.level
        if (!exchangeLog.isEnabledForLevel(level)) {
            return
        }
        // The emission scope OWNS the trace keys ([MdcScope]): the encoder emits the traceId/spanId the
        // request went out with, never a stale bridge id of the closing thread.
        val mdcScope = MdcScope(exchange.requestId, exchange.method, exchange.target, exchange.traceId, exchange.spanId, ownsTraceKeys = true)
        try {
            logEvent(exchange, classification, level, status, elapsedNanos / NANOS_PER_MS, slow)
        } finally {
            restoreQuietly(mdcScope, exchange)
        }
    }

    /**
     * The SLF4J level carries the severity, adapter_outcome the semantic - decoupled on purpose (see
     * ClientLogField.OUTCOME): a timeout is WARN with its own outcome (the peer is slow, not broken),
     * any other thrown call is ERROR, a 5xx answer without an exception is WARN (the peer answered, the
     * application decides what to make of it); all of the latter two carry "failure".
     */
    private fun classify(
        failure: Throwable?,
        status: Int?,
    ): Classification =
        when {
            failure != null && Timeouts.isTimeout(failure) -> Classification(Level.WARN, ClientOutcome.TIMEOUT, failure)
            failure != null -> Classification(Level.ERROR, ClientOutcome.FAILURE, failure)
            (status ?: 0) >= 500 -> Classification(Level.WARN, ClientOutcome.FAILURE, null)
            else -> Classification(Level.INFO, ClientOutcome.SUCCESS, null)
        }

    /** The one immutable builder chain of the completion event; optional fields are left off by the *IfPresent helpers. */
    private fun logEvent(
        exchange: Exchange,
        classification: Classification,
        level: Level,
        status: Int?,
        durationMs: Long,
        slow: Boolean,
    ) {
        val headers = exchange.responseHeaders
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

    /** Multi-value resolution, like the request side: a single-value getFirst would silently truncate repeated headers (Set-Cookie being the classic). */
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
     * Restoration guarded on its own, like the interceptor's call scope: a throwing MDC adapter here must
     * neither be reported as a LOST emission (the event is already on the logger) nor mask an emission
     * failure propagating out of the try - it costs the restoration, counted as stage=wiring.
     */
    private fun restoreQuietly(
        scope: MdcScope,
        exchange: Exchange,
    ) {
        try {
            scope.close()
        } catch (e: Exception) {
            reportQuietly {
                metrics.wiringFailure()
                internalLog.warn(
                    "MDC restoration failed after emitting {} {} - the emitting thread may carry stale client keys: {}",
                    exchange.method,
                    exchange.target,
                    e.toString(),
                    e,
                )
            }
        }
    }

    /**
     * The opt-in body measurements: the size samples, and - for the response side - the read-state
     * counter, which is what tells an unread body from an absent one (the size sample cannot: both are
     * zero bytes and record nothing). Both response measurements need a response: a call that never got
     * an answer has no body to consume.
     *
     * The REQUEST sample needs a response too. The interceptor copies the serialized body BEFORE the
     * wire call - a connection refused or a connect timeout later means none of those bytes reached the
     * peer - and the interceptor API offers no seam at the actual write. A response is the one proof this
     * seam has that the request went out, so the size meter (documented as bytes that flowed) records the
     * sample only then; the `adapter_request_body` FIELD is documented differently - the body the client
     * handed to the wire call - and stays on the line of a failed call as the evidence it is.
     */
    private fun recordBodySizes(exchange: Exchange) {
        if (properties.measureRequestBodySize && exchange.response != null) {
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
