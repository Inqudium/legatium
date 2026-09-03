package eu.inqudium.legatium.webclient.logging

import eu.inqudium.legatium.common.BodyReadState
import eu.inqudium.legatium.common.reportQuietly
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The module's meters (the `*_METER` constants below), all fed from the host's registry. Every meter
 * here observes what neither `http.client.requests` nor the log fields can show; rates, latencies and
 * status distributions are deliberately left to those.
 *
 * All fixed-tag meters are PRE-registered at construction: a `rate()` alert must see the zero before the
 * first occurrence, not a meter that springs into existence at the very moment it should already fire.
 *
 * ONE INSTANCE PER REGISTRY, enforced by [forRegistry]: Micrometer deduplicates meters by id, so a
 * second instance of this class against the same registry would share the counters (harmless -
 * increments merge) but NOT the gauge: the second gauge registration is silently ignored and that
 * instance's open-exchange movements become invisible. Every filter therefore obtains its metrics
 * through [forRegistry], and filters on one registry share one owner - the gauge then reports the
 * total open exchanges across them.
 *
 * FAIL-OPEN REGISTRATION: Micrometer rejects a registration whose id already exists with a different
 * meter type (a host or another library owning a `client.*` name). Unguarded, that throw at
 * construction would abort the application context - a logging library must not - and at the lazy
 * body-size registration would suppress the exchange event. Every registration therefore falls back to a
 * private [SimpleMeterRegistry] for the conflicting meter, logged once per meter name: the module keeps
 * working and the affected meter is simply not exported (twin parity with the RestClient module).
 */
internal class ClientLoggingMetrics private constructor(
    private val meterRegistry: MeterRegistry,
) {
    private val fallbackRegistry = SimpleMeterRegistry()
    private val reportedConflicts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Registers through [register] against the host registry; on rejection the meter lands in the
     * private fallback registry instead, with one warning per meter name.
     */
    private fun <M : Meter> registerOrFallback(
        meterName: String,
        register: (MeterRegistry) -> M,
    ): M =
        try {
            register(meterRegistry)
        } catch (e: Exception) {
            if (reportedConflicts.add(meterName)) {
                internalLog.warn(
                    "Meter {} could not be registered in the host registry and is kept private (not exported): {}",
                    meterName,
                    e.toString(),
                )
            }
            register(fallbackRegistry)
        }

    // One counter per fail-open site. The metric exists because the failure it counts is the one state
    // logs cannot reliably show: when the emission breaks, the missing exchange line IS the symptom, and
    // the report about it is itself only a log line in the same possibly-broken pipeline. A counter
    // travels the independent metrics channel.
    private val failOpenCounters =
        listOf(STAGE_EMISSION, STAGE_ARRIVAL, STAGE_WIRING).associateWith { stage ->
            registerOrFallback(FAIL_OPEN_METER) { registry ->
                Counter
                    .builder(FAIL_OPEN_METER)
                    .tag("stage", stage)
                    .description(
                        "Logging failures swallowed by the fail-open path; each increment is a lost or " +
                            "degraded log emission that never disturbed its outbound call",
                    ).register(registry)
            }
        }

    // Counts EMITTED exchange events - after the level gate, arrival lines excluded - so its sum is the
    // ground truth to reconcile against the log index: any difference is loss in the log pipeline
    // (appender overflow, broker loss, index rejection), isolated from application behavior.
    private val eventCounters =
        listOf(OUTCOME_SUCCESS, OUTCOME_FAILURE, OUTCOME_TIMEOUT, OUTCOME_CANCELLED).associateWith { outcome ->
            registerOrFallback(EVENTS_METER) { registry ->
                Counter
                    .builder(EVENTS_METER)
                    .tag("outcome", outcome)
                    .description(
                        "Structured exchange events actually emitted on the exchange logger; reconcile " +
                            "against the log index to detect log-pipeline loss",
                    ).register(registry)
            }
        }

    // The liveness check of the emission architecture itself: since the event is emitted at the body's
    // terminal signal, everything rests on the application subscribing to (or releasing) every response
    // body it was handed. A body that is never subscribed loses the event SILENTLY - nothing throws, so
    // not even the fail-open counter sees it. This gauge (up at filter entry, down at completion) makes
    // the assumption measurable.
    private val openExchanges =
        AtomicLong(0).also { open ->
            registerOrFallback(OPEN_EXCHANGES_METER) { registry ->
                Gauge
                    .builder(OPEN_EXCHANGES_METER, open) { it.get().toDouble() }
                    .description(
                        "Exchanges between filter entry and the response body's terminal signal; a growing " +
                            "baseline means response bodies are never consumed or released and exchange events " +
                            "are silently lost",
                    ).register(registry)
            }
        }

    // Watches the identity contract with the host's propagation: a rising `generated` share means the
    // application stopped propagating traceparent (or a correlation header) onto its outbound calls - a
    // regression neither logs nor other metrics surface reliably.
    private val requestIdSourceCounters =
        listOf(REQUEST_ID_SOURCE_TRACE, REQUEST_ID_SOURCE_HEADER, REQUEST_ID_SOURCE_GENERATED).associateWith { source ->
            registerOrFallback(CORRELATION_METER) { registry ->
                Counter
                    .builder(CORRELATION_METER)
                    .tag("source", source)
                    .description(
                        "Origin of the exchange's request id: the traceparent trace id, " +
                            "the correlation header already on the request, or generated and sent (ADR-0002)",
                    ).register(registry)
            }
        }

    fun emissionFailure() = failOpenCounters.getValue(STAGE_EMISSION).increment()

    fun arrivalFailure() = failOpenCounters.getValue(STAGE_ARRIVAL).increment()

    fun wiringFailure() = failOpenCounters.getValue(STAGE_WIRING).increment()

    /**
     * Counts one EMITTED exchange event; [outcome] must be one of the [OUTCOME_SUCCESS] family. Guarded:
     * the event is already on the logger when this runs, so a failing host counter must neither be
     * reported as a lost emission nor disturb the caller.
     */
    fun eventEmitted(outcome: String) = updateQuietly(EVENTS_METER) { eventCounters.getValue(outcome).increment() }

    fun exchangeOpened() {
        openExchanges.incrementAndGet()
    }

    fun exchangeCompleted() {
        openExchanges.decrementAndGet()
    }

    /**
     * Counts the request-id origin; [source] must be one of the [REQUEST_ID_SOURCE_TRACE] family.
     * Guarded like [eventEmitted]: a throwing host counter must not degrade the call to an unlogged
     * pass-through.
     */
    fun requestId(source: String) =
        updateQuietly(CORRELATION_METER) {
            requestIdSourceCounters.getValue(source).increment()
        }

    /**
     * Isolates an OPERATIONAL counter update from the exchange it observes: registration succeeded, but
     * a host `Counter` may still throw on increment. The failure is counted `stage=wiring` (bookkeeping
     * lost, event unaffected) and warned; the fail-open counter itself is reported through [reportQuietly],
     * so a registry broken as a whole is silently dropped rather than escaping.
     */
    private inline fun updateQuietly(
        meterName: String,
        update: () -> Unit,
    ) {
        try {
            update()
        } catch (e: Exception) {
            reportQuietly {
                wiringFailure()
                internalLog.warn("Meter {} could not be updated - the exchange is logged without it: {}", meterName, e.toString())
            }
        }
    }

    fun requestBodySize(
        template: String?,
        host: String?,
        bytes: Long,
    ) = recordBodySize(REQUEST_BODY_SIZE_METER, template, host, bytes)

    fun responseBodySize(
        template: String?,
        host: String?,
        bytes: Long,
    ) = recordBodySize(RESPONSE_BODY_SIZE_METER, template, host, bytes)

    /**
     * Counts one exchange under how far the application consumed the RESPONSE body, tagged by the
     * low-cardinality URI template and the peer host - see [RESPONSE_BODY_READ_METER]. Created per
     * `uri`/`host`/`state` on first use, like the body-size summaries (Micrometer deduplicates by id);
     * recorded whenever a response capture exists in measuring mode and a response was received,
     * INCLUDING answers the application released without reading - that is exactly the `unread` share
     * the counter exists to show (a `releaseBody` DOES subscribe and drain, and counts as `complete`;
     * only a body nobody ever subscribed to is `unread`).
     */
    fun responseBodyRead(
        template: String?,
        host: String?,
        state: BodyReadState,
    ) = registerOrFallback(RESPONSE_BODY_READ_METER) { registry ->
        Counter
            .builder(RESPONSE_BODY_READ_METER)
            .description("Exchanges by how far the application consumed the response body: unread, partial, or complete")
            .tag("uri", template ?: UNTEMPLATED_URI)
            .tag("host", host ?: UNKNOWN_HOST)
            .tag("state", state.tagValue)
            .register(registry)
    }.increment()

    /**
     * Bytes that ACTUALLY flowed, tagged by the low-cardinality URI template and the peer host. A
     * zero-byte body records no sample - the distribution describes bodies that exist, and the sum stays
     * exact either way. The summaries are created per tag set on first use; Micrometer's registry
     * deduplicates by id.
     */
    private fun recordBodySize(
        meterName: String,
        template: String?,
        host: String?,
        bytes: Long,
    ) {
        if (bytes == 0L) {
            return
        }
        registerOrFallback(meterName) { registry ->
            DistributionSummary
                .builder(meterName)
                .baseUnit("bytes")
                .description("Bytes of the body that actually flowed through the exchange")
                .tag("uri", template ?: UNTEMPLATED_URI)
                .tag("host", host ?: UNKNOWN_HOST)
                .register(registry)
        }.record(bytes.toDouble())
    }

    companion object {
        private val internalLog = LoggerFactory.getLogger(ClientLoggingMetrics::class.java)

        // Both sides weak: the KEY must not pin a host registry that outlives its context, and the
        // VALUE is exactly what every filter already holds strongly - the owner lives as long as a
        // filter using it does. Residual (accepted): when every filter of a still-live registry has
        // been collected and a NEW one is wired against it afterwards, the fresh owner meets its own
        // pre-registered meter ids again and the ignored-gauge case resurfaces - a churn pattern
        // neither the auto-configuration nor per-test registries produce.
        private val perRegistry = WeakHashMap<MeterRegistry, WeakReference<ClientLoggingMetrics>>()

        /**
         * The metrics owner for [registry] - created on first use, SHARED by every later caller with
         * the same registry. Sharing is what keeps the open-exchanges gauge truthful when several
         * filters run against one registry: a duplicate owner's gauge registration would be silently
         * ignored (see the class documentation), a shared owner makes the gauge the total across its
         * filters while the counters merge as before.
         */
        fun forRegistry(registry: MeterRegistry): ClientLoggingMetrics =
            synchronized(perRegistry) {
                perRegistry[registry]?.get()
                    ?: ClientLoggingMetrics(registry).also { perRegistry[registry] = WeakReference(it) }
            }

        /**
         * Meter counting logging failures the fail-open path swallowed, tagged `stage=emission` (the
         * exchange event was LOST), `stage=arrival` (the optional start line was lost) or `stage=wiring`
         * (wiring or bookkeeping around the call failed; a pre-call wiring failure degrades to an
         * unlogged pass-through, a post-call one usually still emits the event). Calls are never
         * affected by what this counts - that is the fail-open contract; the counter makes its price
         * visible on a channel independent of the possibly-broken log pipeline.
         */
        const val FAIL_OPEN_METER = "client.logging.failopen"

        /**
         * Meter counting the exchange events actually EMITTED (after the level gate; arrival lines are
         * not counted), tagged `outcome`. Its sum is the ground truth for reconciling metric-side event
         * counts against the log index: any difference is loss in the log pipeline itself.
         */
        const val EVENTS_METER = "client.logging.events"

        /** Distribution of request body bytes that actually flowed, tagged `uri` (template) and `host`. */
        const val REQUEST_BODY_SIZE_METER = "client.request.body.size"

        /** Distribution of response body bytes that actually flowed, tagged `uri` (template) and `host`. */
        const val RESPONSE_BODY_SIZE_METER = "client.response.body.size"

        /**
         * Counter of exchanges by response-body consumption, tagged `uri` (template), `host` and `state`
         * (`unread` | `partial` | `complete`, see [BodyReadState]). The body tee mirrors CONSUMPTION,
         * not transmission: the logged body and the size sample describe the bytes the application read,
         * so neither can tell a body the peer sent but the application ignored (a `toBodilessEntity`, an
         * error status whose body was dropped) from one that was never sent. This counter is the one
         * place that distinction is visible - a call site with a rising `unread` or `partial` share is
         * discarding payload it paid for. Opt-in with `measure-response-body-size`, like the size
         * summary.
         */
        const val RESPONSE_BODY_READ_METER = "client.response.body.read"

        /** The `uri` tag value for exchanges the client recorded no URI template for. */
        const val UNTEMPLATED_URI = "UNKNOWN"

        /** The `host` tag value for exchanges whose request URI carries no host. */
        const val UNKNOWN_HOST = "UNKNOWN"

        /**
         * Gauge of exchanges between filter entry (wiring) and the exactly-once completion at the
         * response body's terminal signal. Hovers near the in-flight call count in health; a
         * monotonically growing baseline means response bodies are never subscribed to or released and
         * exchange events are being lost SILENTLY - the one failure mode neither the fail-open counter
         * (nothing throws) nor the events counter (no baseline) can see.
         */
        const val OPEN_EXCHANGES_METER = "client.logging.exchanges.open"

        /**
         * Counter of request-id origins, tagged `source=trace|header|generated` (ADR-0002). A rising
         * `generated` share means the application stopped propagating `traceparent` or a correlation
         * header onto its outbound calls.
         */
        const val CORRELATION_METER = "client.logging.correlation.id"

        /** The closed outcome vocabulary - shared with the emitter, so counter keys and log field agree. */
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_FAILURE = "failure"
        const val OUTCOME_TIMEOUT = "timeout"
        const val OUTCOME_CANCELLED = "cancelled"

        private const val STAGE_EMISSION = "emission"
        private const val STAGE_ARRIVAL = "arrival"
        private const val STAGE_WIRING = "wiring"

        /** The closed request-id source vocabulary of [CORRELATION_METER] - shared with the filter. */
        const val REQUEST_ID_SOURCE_TRACE = "trace"
        const val REQUEST_ID_SOURCE_HEADER = "header"
        const val REQUEST_ID_SOURCE_GENERATED = "generated"
    }
}
