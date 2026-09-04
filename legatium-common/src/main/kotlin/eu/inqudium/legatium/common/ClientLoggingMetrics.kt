package eu.inqudium.legatium.common

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference
import java.util.EnumMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The client stack a twin serves - the ONLY facts of the shared metrics owner that differ per twin: the
 * `client` tag of the open-exchanges gauge, the outcome vocabulary of the events counter, and the
 * wording of the gauge's description. Everything else about the six meters is one cross-stack contract
 * (ADR-0003, amendment of 2026-09-04).
 */
internal enum class ClientStack(
    /** The `client` tag value of the open-exchanges gauge. */
    val tag: String,
    /** The closed outcome vocabulary of this stack, pre-registered on the events counter. */
    val outcomes: List<String>,
    /** The stack's own wording of what an open exchange is. */
    val openExchangesDescription: String,
) {
    RESTCLIENT(
        "restclient",
        listOf(ClientLoggingMetrics.OUTCOME_SUCCESS, ClientLoggingMetrics.OUTCOME_FAILURE, ClientLoggingMetrics.OUTCOME_TIMEOUT),
        "Exchanges between interceptor entry and response close; a growing baseline means " +
            "responses are not being closed and exchange events are silently lost",
    ),
    WEBCLIENT(
        "webclient",
        listOf(
            ClientLoggingMetrics.OUTCOME_SUCCESS,
            ClientLoggingMetrics.OUTCOME_FAILURE,
            ClientLoggingMetrics.OUTCOME_TIMEOUT,
            ClientLoggingMetrics.OUTCOME_CANCELLED,
        ),
        "Exchanges between filter entry and the response body's terminal signal; a growing " +
            "baseline means response bodies are never consumed or released and exchange events " +
            "are silently lost",
    ),
}

/**
 * The module's meters (the `*_METER` constants below), all fed from the host's registry - ONE
 * implementation for both twins, parameterised by the [ClientStack] (ADR-0003, amendment of
 * 2026-09-04: the two copies had converged to 95 % identity). Every meter here observes what neither
 * `http.client.requests` nor the log fields can show; rates, latencies and status distributions are
 * deliberately left to those.
 *
 * All fixed-tag meters are PRE-registered at construction: a `rate()` alert must see the zero before the
 * first occurrence, not a meter that springs into existence at the very moment it should already fire.
 *
 * ONE INSTANCE PER REGISTRY AND STACK, enforced by [forRegistry]: Micrometer deduplicates meters by id,
 * so a second instance of this class against the same registry would share the counters (harmless -
 * increments merge) but NOT the gauge: the second gauge registration is silently ignored and that
 * instance's open-exchange movements become invisible. Every interceptor or filter therefore obtains its
 * metrics through [forRegistry], and entry points on one registry share one owner - the gauge then
 * reports the total open exchanges across them. The two twins' gauges carry different `client` tags
 * and therefore different ids, so a host carrying both twins gets both.
 *
 * FAIL-OPEN REGISTRATION - a decided trade: Micrometer rejects a registration whose id already exists
 * with a different meter type (a host or another library owning an `adapter.*` name). Unguarded, that
 * throw at construction would abort the application context - a logging library must not - and at the
 * lazy body-size registration would suppress the exchange event. Every registration therefore falls
 * back to a private [SimpleMeterRegistry] for the conflicting meter, logged once per meter name: the
 * module keeps working and the affected meter is simply not exported. The alternatives were weighed
 * (architecture review of 2026-09-04, finding 7): failing the context start contradicts the fail-open
 * promise, and "not registering" needs a per-type no-op meter and is not simpler than one private
 * registry. The scenario is rare; the shape stays because it is the cheapest one that never throws.
 */
internal class ClientLoggingMetrics private constructor(
    private val meterRegistry: MeterRegistry,
    private val stack: ClientStack,
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
        stack.outcomes.associateWith { outcome ->
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

    // The liveness check of the emission architecture itself: the event is emitted when the exchange
    // truly ends (response close, or the body's terminal signal), and everything rests on the
    // application closing or consuming every response it was handed. A response that never ends loses
    // the event SILENTLY - nothing throws, so not even the fail-open counter sees it. This gauge (up at
    // entry, down at completion) makes the assumption measurable.
    private val openExchanges =
        AtomicLong(0).also { open ->
            registerOrFallback(OPEN_EXCHANGES_METER) { registry ->
                Gauge
                    .builder(OPEN_EXCHANGES_METER, open) { it.get().toDouble() }
                    // Tagged per twin: Micrometer deduplicates by id and would silently keep the FIRST
                    // gauge registered under a bare name, so in a host carrying both twins the second
                    // twin's open exchanges would vanish. Two ids, two gauges; sum over the tag for the total.
                    .tag(CLIENT_TAG, stack.tag)
                    .description(stack.openExchangesDescription)
                    .register(registry)
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
     * Counts one EMITTED exchange event; [outcome] must be one of this stack's [ClientStack.outcomes].
     * Guarded: the event is already on the logger when this runs, so a failing host counter must
     * neither be reported as a lost emission nor disturb the caller.
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
     * URI template and the peer host - see [RESPONSE_BODY_READ_METER]. Created per `uri`/`host`/`state`
     * on first use, like the body-size summaries (Micrometer deduplicates by id); recorded whenever a
     * response capture exists in measuring mode and a response was received, INCLUDING answers the
     * application released without reading - that is exactly the `unread` share the counter exists to
     * show.
     */
    fun responseBodyRead(
        template: String?,
        host: String?,
        state: BodyReadState,
    ) = registerOrFallback(RESPONSE_BODY_READ_METER) { registry ->
        Counter
            .builder(RESPONSE_BODY_READ_METER)
            .description("Exchanges by how far the application consumed the response body: unread, partial, or complete")
            .tag("uri", uriTag(template))
            .tag("host", host ?: UNKNOWN_HOST)
            .tag("state", state.tagValue)
            .register(registry)
    }.increment()

    /**
     * Bytes that ACTUALLY flowed, tagged by the URI template (low-cardinality by construction: a recorded
     * value without a placeholder is folded to [UNTEMPLATED_URI], see [uriTag]) and the peer host - which
     * is caller-controlled and therefore a documented precondition of the opt-in measuring properties. A
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
                .tag("uri", uriTag(template))
                .tag("host", host ?: UNKNOWN_HOST)
                .register(registry)
        }.record(bytes.toDouble())
    }

    companion object {
        private val internalLog = LoggerFactory.getLogger(ClientLoggingMetrics::class.java)

        // Both sides weak: the KEY must not pin a host registry that outlives its context, and the
        // VALUE is exactly what every entry point already holds strongly - the owner lives as long as
        // an interceptor or filter using it does. Residual (accepted): when every entry point of a
        // still-live registry has been collected and a NEW one is wired against it afterwards, the
        // fresh owner meets its own pre-registered meter ids again and the ignored-gauge case
        // resurfaces - a churn pattern neither the auto-configurations nor per-test registries produce.
        private val perRegistry = WeakHashMap<MeterRegistry, EnumMap<ClientStack, WeakReference<ClientLoggingMetrics>>>()

        /**
         * The metrics owner for [registry] and [stack] - created on first use, SHARED by every later
         * caller with the same registry and stack. Sharing is what keeps the open-exchanges gauge
         * truthful when several entry points run against one registry: a duplicate owner's gauge
         * registration would be silently ignored (see the class documentation), a shared owner makes
         * the gauge the total across its entry points while the counters merge as before.
         */
        fun forRegistry(
            registry: MeterRegistry,
            stack: ClientStack,
        ): ClientLoggingMetrics =
            synchronized(perRegistry) {
                val owners = perRegistry.getOrPut(registry) { EnumMap(ClientStack::class.java) }
                owners[stack]?.get() ?: ClientLoggingMetrics(registry, stack).also { owners[stack] = WeakReference(it) }
            }

        /**
         * Meter counting logging failures the fail-open path swallowed, tagged `stage=emission` (the
         * exchange event was LOST), `stage=arrival` (the optional start line was lost) or `stage=wiring`
         * (wiring or bookkeeping around the call failed; a pre-call wiring failure degrades to an
         * unlogged pass-through, a post-call one usually still emits the event). Calls are never
         * affected by what this counts - that is the fail-open contract; the counter makes its price
         * visible on a channel independent of the possibly-broken log pipeline.
         */
        const val FAIL_OPEN_METER = "adapter.logging.failopen"

        /**
         * Meter counting the exchange events actually EMITTED (after the level gate; arrival lines are
         * not counted), tagged `outcome`. Its sum is the ground truth for reconciling metric-side event
         * counts against the log index: any difference is loss in the log pipeline itself.
         */
        const val EVENTS_METER = "adapter.logging.events"

        /** Distribution of request body bytes that actually flowed, tagged `uri` (template) and `host`. */
        const val REQUEST_BODY_SIZE_METER = "adapter.request.body.size"

        /** Distribution of response body bytes that actually flowed, tagged `uri` (template) and `host`. */
        const val RESPONSE_BODY_SIZE_METER = "adapter.response.body.size"

        /**
         * Counter of exchanges by response-body consumption, tagged `uri` (template), `host` and `state`
         * (`unread` | `partial` | `complete`, see [BodyReadState]). The body tee mirrors CONSUMPTION,
         * not transmission: the logged body and the size sample describe the bytes the application read,
         * so neither can tell a body the peer sent but the application ignored from one that was never
         * sent. This counter is the one place that distinction is visible - a call site with a rising
         * `unread` or `partial` share is discarding payload it paid for. Opt-in with
         * `measure-response-body-size`, like the size summary.
         */
        const val RESPONSE_BODY_READ_METER = "adapter.response.body.read"

        /**
         * The `uri` tag value for exchanges the client recorded no URI template for - and for a recorded
         * "template" without a placeholder: the client records whatever string `uri(String, ...)` was
         * given, so `uri("/things/" + id)` would otherwise put one tag value per id on the meter.
         */
        const val UNTEMPLATED_URI = "UNKNOWN"

        /** The `host` tag value for exchanges whose request URI carries no host. */
        const val UNKNOWN_HOST = "UNKNOWN"

        /** The `client` tag of the open-exchanges gauge, distinguishing the two twins' gauges in one registry. */
        const val CLIENT_TAG = "client"

        /** The `uri` tag for a recorded template: the template itself when it carries a placeholder, [UNTEMPLATED_URI] otherwise. */
        fun uriTag(template: String?): String = template?.takeIf { '{' in it } ?: UNTEMPLATED_URI

        /**
         * Gauge of exchanges between entry (wiring) and the exactly-once completion, tagged `client`
         * (`restclient` | `webclient`). Hovers near the in-flight call count in health; a monotonically
         * growing baseline means exchanges never end and exchange events are being lost SILENTLY - the
         * one failure mode neither the fail-open counter (nothing throws) nor the events counter (no
         * baseline) can see.
         */
        const val OPEN_EXCHANGES_METER = "adapter.logging.exchanges.open"

        /**
         * Counter of request-id origins, tagged `source=trace|header|generated` (ADR-0002). A rising
         * `generated` share means the application stopped propagating `traceparent` or a correlation
         * header onto its outbound calls.
         */
        const val CORRELATION_METER = "adapter.logging.correlation.id"

        /** The closed outcome vocabulary - shared with the emitters, so counter keys and log field agree. */
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_FAILURE = "failure"
        const val OUTCOME_TIMEOUT = "timeout"

        /** The reactive stack's own disposition - a subscription the caller abandoned; never emitted by the blocking twin. */
        const val OUTCOME_CANCELLED = "cancelled"

        private const val STAGE_EMISSION = "emission"
        private const val STAGE_ARRIVAL = "arrival"
        private const val STAGE_WIRING = "wiring"

        /** The closed request-id source vocabulary of [CORRELATION_METER] - shared with the entry points. */
        const val REQUEST_ID_SOURCE_TRACE = "trace"
        const val REQUEST_ID_SOURCE_HEADER = "header"
        const val REQUEST_ID_SOURCE_GENERATED = "generated"
    }
}
