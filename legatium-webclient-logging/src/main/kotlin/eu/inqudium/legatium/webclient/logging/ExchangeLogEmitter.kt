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
import eu.inqudium.legatium.common.NoOpScope
import eu.inqudium.legatium.common.Timeouts
import eu.inqudium.legatium.common.TraceMdcKeys
import eu.inqudium.legatium.common.WiringCost
import eu.inqudium.legatium.common.addKeyValue
import eu.inqudium.legatium.common.addKeyValueIfPresent
import eu.inqudium.legatium.common.declaredCharsetOrUtf8
import eu.inqudium.legatium.common.failOpen
import eu.inqudium.legatium.common.reportQuietly
import eu.inqudium.legatium.common.reportWiringFailure
import eu.inqudium.legatium.common.setCauseIfPresent
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.http.HttpHeaders
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Builds and emits the log events of an exchange - the arrival line and the completion event - with the
 * IDENTICAL message and field format of the legatium-restclient-logging emitter (the field family is
 * locked once, by legatium-common's `ClientLogFieldTest` - ADR-0003; the message text by each twin's
 * `TwinContractTest`); only the disposition vocabulary is wider where the stack is (`cancelled`, which
 * a blocking call cannot be).
 *
 * ## Levels
 *
 * The level carries severity only, `adapter_outcome` the semantic - the matrix is [classify]'s, plus
 * the slow escalation (INFO to WARN at [ClientLoggingProperties.slowRequestThreshold], outcome
 * unchanged). Severity and outcome are resolved BEFORE the event is built, so a disabled level costs
 * no assembly.
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
    /**
     * Restores the caller's thread-locals from the exchange's Reactor Context around each emission
     * (ADR-0010). Detected against this module's class loader by default (manual wiring); mutable for
     * the auto-configuration, which re-detects against the context's class loader - the production
     * second user this seam exists for - and for the tests, which swap in a throwing restorer to drive
     * the fail-open path. A post-construction seam rather than a constructor parameter of the filter,
     * so the entry point keeps one constructor signature. The RestClient twin has no counterpart seam:
     * its `CallerMdcSnapshot` (ADR-0011) has one restore and no second implementation, and its tests
     * drive the fail-open path through a failing MDC adapter instead.
     */
    internal var ambientRestorer: AmbientContextRestorer = AmbientContextRestorer.detect(),
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
            // The same scopes as the completion event's (`withEmissionScopes`), so both lines of one
            // exchange carry the same ambient keys.
            withEmissionScopes(exchange) {
                exchangeLog
                    .atInfo()
                    .setMessage(
                        "Adapter http exchange started ${exchange.method} ${exchange.subject} " +
                            "[${MdcKeys.REQUEST_ID}=${exchange.requestId}]",
                    ).addKeyValue(ClientLogField.REQUEST_METHOD, exchange.method)
                    .addKeyValueIfPresent(ClientLogField.NAME, exchange.name)
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
     * The single emission point, called once per exchange by the filter AFTER it won the
     * [Exchange.state] transition to [ExchangeState.COMPLETED] - through
     * [ClientRequestLoggingFilter.complete] (the body's terminal signal, or the response `Mono`'s
     * error/empty signal without a response) or through its cancel path (the caller abandoning the
     * response `Mono`); the transition is the one exactly-once guard.
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
        // is `ClientLoggingProperties.slowRequestThreshold`'s.
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
        withEmissionScopes(exchange) {
            logEvent(exchange, classification, level, status, elapsedNanos / NANOS_PER_MS, slow, response?.headers()?.asHttpHeaders())
        }
    }

    /**
     * Runs [block] - one log statement - inside the two scopes every emission of an exchange opens, and
     * tears them down in reverse order. The caller's thread-locals from the Reactor Context FIRST
     * (ADR-0010) - the join to the server line on an event-loop thread that carries none of them - and
     * the emission scope inside, which OWNS the trace keys ([MdcScope]) exactly like the RestClient
     * twin, so a bridge id the accessors restored never outranks the header's. Both scopes are torn down
     * through [restoreQuietly], inner first.
     */
    private inline fun withEmissionScopes(
        exchange: Exchange,
        block: () -> Unit,
    ) {
        val ambientScope = restoreAmbientQuietly(exchange)
        try {
            val mdcScope = MdcScope(exchange.requestId, exchange.method, exchange.target, exchange.traceId, exchange.spanId, ownsTraceKeys = true)
            try {
                block()
            } finally {
                restoreQuietly(mdcScope, exchange)
            }
        } finally {
            restoreQuietly(ambientScope, exchange)
        }
    }

    /**
     * Scope teardown guarded on its own - the teardown rule of [reportQuietly]: the line counts as
     * emitted, a failure here (an MDC adapter, a host accessor) is bookkeeping (`stage=wiring`).
     */
    private fun restoreQuietly(
        scope: AutoCloseable,
        exchange: Exchange,
    ) {
        try {
            scope.close()
        } catch (e: Exception) {
            reportWiringFailure(
                metrics,
                internalLog,
                WiringCost.DIRTY_TEARDOWN,
                e,
                "Context restoration failed after emitting {} {} - the emitting thread may carry stale keys",
                exchange.method,
                exchange.target,
            )
        }
    }

    /**
     * The caller's context restored, or nothing: a restorer that throws (a host accessor failing on this
     * thread) costs the ambient keys, counted as stage=wiring, never the event - which then carries the
     * module's own identity alone, exactly as before ADR-0010.
     */
    private fun restoreAmbientQuietly(exchange: Exchange): AutoCloseable =
        try {
            ambientRestorer.restore(exchange.ambient)
        } catch (e: Exception) {
            reportWiringFailure(
                metrics,
                internalLog,
                WiringCost.DEGRADED_EVENT,
                e,
                "The caller's context could not be restored for {} {} - the event follows without it",
                exchange.method,
                exchange.target,
            )
            NoOpScope
        }

    /**
     * The SLF4J level carries the severity, adapter_outcome the semantic - decoupled on purpose
     * ([ClientLogField.OUTCOME]), with `cancelled` on top of the RestClient twin's matrix: a timeout in
     * the error's cause chain is WARN with its own outcome (the peer is slow, not broken), any other
     * error signal is ERROR with `failure`, a subscription the caller abandoned (a downstream timeout
     * operator, a disposed caller) is WARN with `cancelled`; an answered exchange classifies by its
     * status alone ([Classification.ofStatus], shared with the RestClient twin): a 5xx is WARN `failure`,
     * a 4xx is `rejected` at INFO - WARN for the [Classification.ESCALATED_REJECTIONS] (ADR-0012) - and
     * INFO `success` otherwise.
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
            else -> Classification.ofStatus(status)
        }

    /**
     * The one immutable builder chain of the completion event; optional fields are left off by the
     * *IfPresent helpers.
     */
    private fun logEvent(
        exchange: Exchange,
        classification: Classification,
        level: Level,
        status: Int?,
        durationMs: Long,
        slow: Boolean,
        headers: HttpHeaders?,
    ) {
        val (requestBody, responseBody) = loggedBodies(exchange, classification.outcome, headers)
        val traceSuffix =
            if (exchange.traceId != null || exchange.spanId != null) {
                " ${TraceMdcKeys.TRACE_ID}=${exchange.traceId ?: "-"} ${TraceMdcKeys.SPAN_ID}=${exchange.spanId ?: "-"}"
            } else {
                ""
            }
        exchangeLog
            .atLevel(level)
            .setMessage(
                "Adapter http exchange ${exchange.method} ${exchange.subject} -> ${status ?: "-"} " +
                    "[${MdcKeys.REQUEST_ID}=${exchange.requestId}$traceSuffix]",
            ).addKeyValue(ClientLogField.OUTCOME, classification.outcome.tagValue)
            .addKeyValue(ClientLogField.DURATION_MS, durationMs)
            .addKeyValue(ClientLogField.REQUEST_METHOD, exchange.method)
            .addKeyValueIfPresent(ClientLogField.RESPONSE_STATUS_CODE, status)
            .addKeyValueIfPresent(ClientLogField.NAME, exchange.name)
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
        // Guarded in `ClientLoggingMetrics.eventEmitted`: the event is already on the logger.
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
     * Body fields only when the direction's [eu.inqudium.legatium.common.BodyLogMode] admits THIS
     * outcome ("failed" = outcome not `success`, which since ADR-0012 includes a `rejected` 4xx); a
     * count-only capture (size metrics) must not surface as an empty field.
     */
    private fun loggedBodies(
        exchange: Exchange,
        outcome: ClientOutcome,
        headers: HttpHeaders?,
    ): Pair<String?, String?> {
        val failed = outcome != ClientOutcome.SUCCESS
        val requestBody = if (properties.logRequestBody.logs(failed)) exchange.requestCapture?.loggedValue(exchange.requestCharset) else null
        val responseBody =
            if (properties.logResponseBody.logs(failed)) {
                exchange.responseCapture?.loggedValue(headers?.declaredCharsetOrUtf8() ?: StandardCharsets.UTF_8)
            } else {
                null
            }
        return requestBody to responseBody
    }

    /**
     * Guarded on its own: whatever the body measurements throw costs the sample, never the event. The
     * meter owner already confines the two host-registry faults itself - a rejected registration falls
     * back to its private registry, a throwing host meter is counted per hit and warned once
     * ([ClientLoggingMetrics]) - so this guard is the last line, warning per exchange because reaching
     * it means the owner's own guards regressed.
     */
    private fun recordBodySizesQuietly(exchange: Exchange) {
        try {
            recordBodySizes(exchange)
        } catch (e: Exception) {
            reportWiringFailure(
                metrics,
                internalLog,
                WiringCost.DEGRADED_EVENT,
                e,
                "Body size could not be recorded for {} {} - the event follows without it",
                exchange.method,
                exchange.target,
            )
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
            exchange.requestCapture?.let { metrics.requestBodySize(exchange.uriTemplate, exchange.host, exchange.name, it.totalBytes) }
        }
        if (properties.measureResponseBodySize) {
            exchange.responseCapture?.let {
                metrics.responseBodySize(exchange.uriTemplate, exchange.host, exchange.name, it.totalBytes)
                if (exchange.response != null) {
                    metrics.responseBodyRead(exchange.uriTemplate, exchange.host, exchange.name, it.readState)
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
