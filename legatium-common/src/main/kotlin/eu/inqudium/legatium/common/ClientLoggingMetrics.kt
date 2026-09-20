package eu.inqudium.legatium.common

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.noop.NoopMeter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference
import java.util.EnumMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The disposition of one exchange - the value of `adapter_outcome` and the `outcome` tag of the events
 * counter. A closed set, like every wire-bound vocabulary of the family: the tag value is the contract,
 * the constant is the code's name for it. The value names WHO is responsible for the disposition
 * (ADR-0012): nobody for a [SUCCESS], the caller - this application - for a [REJECTED] 4xx, the peer or
 * the call itself for a [FAILURE], the clock for a [TIMEOUT]. [CANCELLED] exists on the reactive stack
 * only. The level carries the severity separately ([Classification]).
 */
internal enum class ClientOutcome(
    val tagValue: String,
) {
    SUCCESS("success"),
    REJECTED("rejected"),
    FAILURE("failure"),
    TIMEOUT("timeout"),
    CANCELLED("cancelled"),
}

/** The `stage` tag of the fail-open counter: where a logging failure was swallowed. */
internal enum class FailOpenStage(
    val tagValue: String,
) {
    /** The exchange event was LOST. */
    EMISSION("emission"),

    /** The optional start line was lost. */
    ARRIVAL("arrival"),

    /** Wiring or bookkeeping around the call failed; the event usually still follows. */
    WIRING("wiring"),
}

/** The `source` tag of the correlation counter: where an exchange's request id came from (ADR-0002). */
internal enum class RequestIdSource(
    val tagValue: String,
) {
    TRACE("trace"),
    HEADER("header"),
    GENERATED("generated"),
}

/**
 * The client stack a twin serves - the ONLY facts of the shared metrics owner that differ per twin: the
 * `client` tag of the open-exchanges gauge, the outcome vocabulary of the events counter, and the
 * wording of the gauge's description. Everything else about the meters is one cross-stack contract
 * (ADR-0003).
 */
internal enum class ClientStack(
    /** The `client` tag value of the open-exchanges gauge. */
    val tagValue: String,
    /** The closed outcome vocabulary of this stack, pre-registered on the events counter. */
    val outcomes: List<ClientOutcome>,
    /** The stack's own wording of what an open exchange is. */
    val openExchangesDescription: String,
) {
    RESTCLIENT(
        "restclient",
        listOf(ClientOutcome.SUCCESS, ClientOutcome.REJECTED, ClientOutcome.FAILURE, ClientOutcome.TIMEOUT),
        "Exchanges between interceptor entry and response close; a growing baseline means " +
            "responses are not being closed and exchange events are silently lost",
    ),
    WEBCLIENT(
        "webclient",
        ClientOutcome.entries,
        "Exchanges between filter entry and the response body's terminal signal; a growing " +
            "baseline means response bodies are never consumed or released and exchange events " +
            "are silently lost",
    ),
}

/**
 * The module's meters - SIX meter families under SEVEN meter names (the request and response body-size
 * summaries are one family), the `*_METER` constants below - all fed from the host's registry: ONE
 * implementation for both twins, parameterised by the [ClientStack] (ADR-0003). Every meter here
 * observes what neither `http.client.requests` nor the log fields can show; rates, latencies and status
 * distributions are deliberately left to those (ADR-0008).
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
 * module keeps working and the affected meter is simply not exported. Failing the context start would
 * contradict the fail-open promise, and "not registering" needs a per-type no-op meter - one private
 * registry is the cheapest shape that never throws (ADR-0008).
 *
 * The GAUGE has a second collision case Micrometer does not reject: an id of the SAME type that already
 * exists (a host gauge under this name and `client` tag, an older copy of this library on another
 * classloader) is returned as-is, and the state function of the new registration is silently dropped -
 * this instance would then count into an `AtomicLong` nobody exports. The gauge registration therefore
 * checks the host registry for an existing meter under its exact name and tag FIRST and, if one is
 * there, takes the same private-registry path with the same one-time warning: a visibly degraded gauge
 * instead of a silently wrong one. (Counters and summaries have no such case: an existing counter of
 * the same id is the shared meter, and increments merge.)
 *
 * The DYNAMIC body meters (the two size summaries and the read-state counter, tagged per URI template,
 * host and client name) are resolved ONCE per tag set and kept in a cache that mirrors the registry's
 * entries under those three names - one entry per meter the registry HOLDS, so the cache adds no
 * cardinality and needs no size policy of its own; the `uri` folding ([uriTag]) and the documented host
 * precondition bound both alike. A meter the registry did NOT keep - a denying `MeterFilter` (Boot's
 * `management.metrics.enable.*`, a tag cap) or a closed registry answers with a detached no-op instance
 * - is used for its exchange but never cached: nothing would ever release it, and the cache would grow
 * per tag set exactly where the operator bounded the registry. Without the cache every measured exchange
 * rebuilt the builder, the tags and the `Meter.Id` three times only to hit Micrometer's deduplicating
 * lookup (measured in `benchmarks/`: `BodyMeterRecordBenchmark`). The one way cache and registry could
 * drift apart - a host removing one of the dynamic meters - is closed by a removal listener that drops
 * the entry, so the next exchange registers anew instead of recording into a detached instance. The
 * listener covers the dynamic meters ONLY: the fixed meters are registered once at construction, and a
 * host that removes one of them (a `clear()` on a test registry) has decided against it - the owner
 * keeps counting into the detached instance rather than re-registering behind the host's back.
 *
 * LOCK ORDER: Micrometer notifies removal listeners while holding its registry-wide meter-map lock, and
 * registering a new id takes that same lock. The cache is therefore never written from inside a
 * `ConcurrentHashMap.computeIfAbsent` - its mapping function runs under the map's bin lock, and a
 * registration in there would wait for the registry lock while the listener, holding it, waits for the
 * bin lock to drop the removed entry. A miss resolves the meter OUTSIDE the map and publishes it with
 * `putIfAbsent` ([cacheBodyMeter]); a lost race registers the same id twice, which Micrometer
 * deduplicates to one instance anyway.
 *
 * ACCEPTED RESIDUE of that order (defect analysis of 2026-09-19, night, finding 1): between the
 * registration returning and the `putIfAbsent` lies a window of microseconds in which a host removal
 * of that very meter runs the listener against a cache that holds no entry yet; the detached instance
 * is then published and takes every later sample of its tag set, unseen by any exporter, until the
 * owner is recreated. It needs a host that removes meters at runtime (a `clear()`, a pruner) AND the
 * removal inside that window, and it costs the samples of one tag set - never an event, never a call.
 * Closing it would take a registry-wide lookup after every first-time registration; the trade against
 * the deadlock the order removed is deliberate, and the residue is documented rather than paid for.
 */
internal class ClientLoggingMetrics private constructor(
    private val meterRegistry: MeterRegistry,
    private val stack: ClientStack,
) {
    private val fallbackRegistry = SimpleMeterRegistry()
    private val reportedConflicts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val reportedUpdateFailures: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The tag set of one dynamic body meter, already folded the way the tags are ([uriTag], the host and
     * name fallbacks), so every raw input that yields the same meter id shares one entry.
     */
    private data class BodyMeterKey(
        val meterName: String,
        val uriTemplate: String,
        val host: String,
        val name: String,
        val state: String? = null,
    )

    /** [REQUEST_BODY_SIZE_METER] and [RESPONSE_BODY_SIZE_METER], resolved once per tag set (class KDoc). */
    private val bodySizeSummaries = ConcurrentHashMap<BodyMeterKey, DistributionSummary>()

    /** [RESPONSE_BODY_READ_METER], resolved once per tag set and state (class KDoc). */
    private val readStateCounters = ConcurrentHashMap<BodyMeterKey, Counter>()

    init {
        // A host that removes one of the cached meters (`MeterRegistry.remove`) gets it registered anew
        // on the next exchange; without this the owner would keep recording into the detached instance.
        // Runs UNDER the registry's meter-map lock and takes the maps' bin locks - the lock order the
        // class KDoc fixes; the registry also holds this lambda, and with it the owner, as long as it lives.
        meterRegistry.config().onMeterRemoved { removed ->
            bodySizeSummaries.values.removeIf { it === removed }
            readStateCounters.values.removeIf { it === removed }
        }
    }

    /** [FAIL_OPEN_METER], pre-registered per stage. */
    private val failOpenCounters =
        FailOpenStage.entries.associateWith { stage ->
            registerOrFallback(FAIL_OPEN_METER) { registry ->
                Counter
                    .builder(FAIL_OPEN_METER)
                    .tag("stage", stage.tagValue)
                    .description(
                        "Logging failures swallowed by the fail-open path; each increment is a lost or " +
                            "degraded log emission that never disturbed its outbound call",
                    ).register(registry)
            }
        }

    /** [EVENTS_METER], pre-registered per outcome of this stack's vocabulary. */
    private val eventCounters =
        stack.outcomes.associateWith { outcome ->
            registerOrFallback(EVENTS_METER) { registry ->
                Counter
                    .builder(EVENTS_METER)
                    .tag("outcome", outcome.tagValue)
                    .description(
                        "Structured exchange events actually emitted on the exchange logger; reconcile " +
                            "against the log index to detect log-pipeline loss",
                    ).register(registry)
            }
        }

    /** [OPEN_EXCHANGES_METER]: up at entry, down at the exactly-once completion. */
    private val openExchanges =
        AtomicLong(0).also { open ->
            registerOrFallback(
                OPEN_EXCHANGES_METER,
                // The same-type collision case of the class KDoc: a gauge already under this id would keep its own state.
                taken = { registry -> registry.find(OPEN_EXCHANGES_METER).tag(CLIENT_TAG, stack.tagValue).meter() != null },
            ) { registry ->
                Gauge
                    .builder(OPEN_EXCHANGES_METER, open) { it.get().toDouble() }
                    // Tagged per twin: Micrometer deduplicates by id and would silently keep the FIRST
                    // gauge registered under a bare name, so in a host carrying both twins the second
                    // twin's open exchanges would vanish. Two ids, two gauges; sum over the tag for the total.
                    .tag(CLIENT_TAG, stack.tagValue)
                    .description(stack.openExchangesDescription)
                    .register(registry)
            }
        }

    /** [CORRELATION_METER], pre-registered per source. */
    private val requestIdSourceCounters =
        RequestIdSource.entries.associateWith { source ->
            registerOrFallback(CORRELATION_METER) { registry ->
                Counter
                    .builder(CORRELATION_METER)
                    .tag("source", source.tagValue)
                    .description(
                        "Origin of the exchange's request id: the traceparent trace id, " +
                            "the correlation header already on the request, or generated and sent",
                    ).register(registry)
            }
        }

    /**
     * The miss path of the body-meter caches: resolves the meter through [resolve] OUTSIDE [cache] and
     * publishes it with `putIfAbsent`, never `computeIfAbsent` (the lock order of the class KDoc); a
     * racing resolver's instance is the same registry-deduplicated meter, so either one serves. A meter
     * the host registry did not keep ([NoopMeter]: a denying filter or a closed registry) is returned
     * for this exchange but NOT cached - the removal listener could never release it, and one entry per
     * denied tag set would grow the cache exactly where the operator bounded the registry. The window
     * between [resolve] returning and the `putIfAbsent` is the accepted residue of the class KDoc.
     */
    private fun <M : Meter> cacheBodyMeter(
        cache: ConcurrentHashMap<BodyMeterKey, M>,
        key: BodyMeterKey,
        resolve: () -> M,
    ): M {
        val meter = resolve()
        if (meter is NoopMeter) {
            return meter
        }
        return cache.putIfAbsent(key, meter) ?: meter
    }

    fun emissionFailure() = failOpenCounters.getValue(FailOpenStage.EMISSION).increment()

    fun arrivalFailure() = failOpenCounters.getValue(FailOpenStage.ARRIVAL).increment()

    fun wiringFailure() = failOpenCounters.getValue(FailOpenStage.WIRING).increment()

    /**
     * Counts one EMITTED exchange event under [outcome], which must be one of this stack's pre-registered
     * [ClientStack.outcomes] - checked, so a vocabulary violation is reported by name instead of as a bare
     * lookup failure. Guarded: the event is already on the logger when this runs, so a failing host
     * counter must neither be reported as a lost emission nor disturb the caller.
     */
    fun eventEmitted(outcome: ClientOutcome) =
        updateQuietly(EVENTS_METER) {
            checkNotNull(eventCounters[outcome]) { "outcome ${outcome.tagValue} is not in the ${stack.tagValue} vocabulary" }.increment()
        }

    fun exchangeOpened() {
        openExchanges.incrementAndGet()
    }

    fun exchangeCompleted() {
        openExchanges.decrementAndGet()
    }

    /**
     * Counts the request-id origin. Guarded like [eventEmitted]: a throwing host counter must not degrade
     * the call to an unlogged pass-through.
     */
    fun requestId(source: RequestIdSource) =
        updateQuietly(CORRELATION_METER) {
            requestIdSourceCounters.getValue(source).increment()
        }

    fun requestBodySize(
        template: String?,
        host: String?,
        name: String?,
        bytes: Long,
    ) = recordBodySize(REQUEST_BODY_SIZE_METER, template, host, name, bytes)

    fun responseBodySize(
        template: String?,
        host: String?,
        name: String?,
        bytes: Long,
    ) = recordBodySize(RESPONSE_BODY_SIZE_METER, template, host, name, bytes)

    /**
     * Counts one exchange under how far the application consumed the RESPONSE body, tagged by the
     * URI template, the peer host and the client's name - see [RESPONSE_BODY_READ_METER]. Resolved per
     * `uri`/`host`/`name`/`state` on first use and cached, like the body-size summaries (class KDoc);
     * recorded whenever a response capture exists in measuring mode and a response was received,
     * INCLUDING answers the application released without reading - that is exactly the `unread` share
     * the counter exists to show.
     */
    fun responseBodyRead(
        template: String?,
        host: String?,
        name: String?,
        state: BodyReadState,
    ) = updateQuietly(RESPONSE_BODY_READ_METER) {
        val key = BodyMeterKey(RESPONSE_BODY_READ_METER, uriTag(template), host ?: UNKNOWN_HOST, name ?: UNNAMED_ADAPTER, state.tagValue)
        // The plain get first: on the hit path - every exchange but the first per tag set - the key is
        // then the only allocation; the resolver's lambdas are built on a miss alone.
        val counter =
            readStateCounters[key] ?: cacheBodyMeter(readStateCounters, key) {
                registerOrFallback(RESPONSE_BODY_READ_METER) { registry ->
                    Counter
                        .builder(RESPONSE_BODY_READ_METER)
                        .description("Exchanges by how far the application consumed the response body: unread, partial, or complete")
                        .tag("uri", key.uriTemplate)
                        .tag("host", key.host)
                        .tag("name", key.name)
                        .tag("state", state.tagValue)
                        .register(registry)
                }
            }
        counter.increment()
    }

    /**
     * Registers through [register] against the host registry; on rejection - Micrometer refusing the id,
     * or [taken] reporting that the host registry already holds a meter under it whose state would not be
     * this instance's - the meter lands in the private fallback registry instead, with one warning per
     * meter name.
     */
    private fun <M : Meter> registerOrFallback(
        meterName: String,
        taken: (MeterRegistry) -> Boolean = { false },
        register: (MeterRegistry) -> M,
    ): M {
        val rejection =
            try {
                if (!taken(meterRegistry)) {
                    return register(meterRegistry)
                }
                "a meter with this id is already registered and would keep its own state"
            } catch (e: Exception) {
                e.toString()
            }
        if (reportedConflicts.add(meterName)) {
            internalLog.warn(
                "Meter {} could not be registered in the host registry and is kept private (not exported): {}",
                meterName,
                rejection,
            )
        }
        return register(fallbackRegistry)
    }

    /**
     * Isolates an OPERATIONAL meter update from the exchange it observes: registration succeeded, but a
     * host `Counter` or `DistributionSummary` may still throw on update. The failure is counted
     * `stage=wiring` on EVERY call (bookkeeping lost, event unaffected - the count is the measure of the
     * loss) but warned ONCE per meter name, like a registration conflict: a permanently broken host
     * meter is hit up to five times per measured exchange (the two fixed counters and the three body
     * meters), and a warning per hit would drown the module's curated one-time warnings under load. The
     * fail-open counter itself is reported through [reportQuietly], so a registry broken as a whole is
     * silently dropped rather than escaping.
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
                if (reportedUpdateFailures.add(meterName)) {
                    internalLog.warn(
                        "Meter {} could not be updated - the exchange is logged without it; further failures of this meter are counted, not logged: {}",
                        meterName,
                        e.toString(),
                    )
                }
            }
        }
    }

    /**
     * Bytes that ACTUALLY flowed, tagged by the URI template (low-cardinality by construction: a recorded
     * value without a placeholder is folded to [UNTEMPLATED_URI], see [uriTag]), the peer host - which
     * is caller-controlled and therefore a documented precondition of the opt-in measuring properties -
     * and the client's name ([AdapterName], `UNNAMED` for a client the host did not name). A zero-byte
     * body records no sample - the distribution describes bodies that exist, and the sum stays exact
     * either way. The summaries are resolved per tag set on first use and cached (class KDoc). Guarded
     * like the fixed counters ([updateQuietly]): a host summary that throws on record is counted per hit
     * and warned once.
     */
    private fun recordBodySize(
        meterName: String,
        template: String?,
        host: String?,
        name: String?,
        bytes: Long,
    ) {
        if (bytes == 0L) {
            return
        }
        updateQuietly(meterName) {
            val key = BodyMeterKey(meterName, uriTag(template), host ?: UNKNOWN_HOST, name ?: UNNAMED_ADAPTER)
            // The plain get first, as in responseBodyRead: the key is the hit path's only allocation.
            val summary =
                bodySizeSummaries[key] ?: cacheBodyMeter(bodySizeSummaries, key) {
                    registerOrFallback(meterName) { registry ->
                        DistributionSummary
                            .builder(meterName)
                            .baseUnit("bytes")
                            .description("Bytes of the body that actually flowed through the exchange")
                            .tag("uri", key.uriTemplate)
                            .tag("host", key.host)
                            .tag("name", key.name)
                            .register(registry)
                    }
                }
            summary.record(bytes.toDouble())
        }
    }

    companion object {
        private val internalLog = LoggerFactory.getLogger(ClientLoggingMetrics::class.java)

        /**
         * Meter counting logging failures the fail-open path swallowed, tagged `stage=emission` (the
         * exchange event was LOST), `stage=arrival` (the optional start line was lost) or `stage=wiring`
         * (wiring or bookkeeping around the call failed; a pre-call wiring failure degrades to an
         * unlogged pass-through, a post-call one usually still emits the event). Calls are never
         * affected by what this counts - that is the fail-open contract. The meter exists because the
         * failure it counts is the one state logs cannot reliably show: when the emission breaks, the
         * missing exchange line IS the symptom, and the report about it is itself only a log line in the
         * same possibly-broken pipeline - a counter travels the independent metrics channel.
         */
        const val FAIL_OPEN_METER = "adapter.logging.failopen"

        /**
         * Meter counting the exchange events actually EMITTED (after the level gate; arrival lines are
         * not counted), tagged `outcome`. Its sum is the ground truth for reconciling metric-side event
         * counts against the log index: any difference is loss in the log pipeline itself (appender
         * overflow, broker loss, index rejection), isolated from application behavior.
         */
        const val EVENTS_METER = "adapter.logging.events"

        /** Distribution of request body bytes that actually flowed, tagged `uri` (template), `host` and `name`. */
        const val REQUEST_BODY_SIZE_METER = "adapter.request.body.size"

        /** Distribution of response body bytes that actually flowed, tagged `uri` (template), `host` and `name`. */
        const val RESPONSE_BODY_SIZE_METER = "adapter.response.body.size"

        /**
         * Counter of exchanges by response-body consumption, tagged `uri` (template), `host`, `name` and `state`
         * (`unread` | `partial` | `complete`, see [BodyReadState]). The body tee mirrors CONSUMPTION,
         * not transmission: the logged body and the size sample describe the bytes the application read,
         * so neither can tell a body the peer sent but the application ignored from one that was never
         * sent. This counter is the one place that distinction is visible - a call site with a rising
         * `unread` or `partial` share is discarding payload it paid for. Opt-in with
         * `measure-response-body-size`, like the size summary.
         */
        const val RESPONSE_BODY_READ_METER = "adapter.response.body.read"

        /**
         * Gauge of exchanges between entry (wiring) and the exactly-once completion, tagged `client`
         * (`restclient` | `webclient`) - the liveness check of the emission architecture itself, which
         * rests on the application closing or consuming every response it was handed. Hovers near the
         * in-flight call count in health; a monotonically growing baseline means exchanges never end
         * and exchange events are being lost SILENTLY - the one failure mode neither the fail-open
         * counter (nothing throws) nor the events counter (no baseline) can see.
         */
        const val OPEN_EXCHANGES_METER = "adapter.logging.exchanges.open"

        /**
         * Counter of request-id origins, tagged `source=trace|header|generated` (ADR-0002) - the watch on
         * the identity contract with the host's propagation: a rising `generated` share means the
         * application stopped propagating `traceparent` or a correlation header onto its outbound calls,
         * a regression neither logs nor other metrics surface reliably.
         */
        const val CORRELATION_METER = "adapter.logging.correlation.id"

        /**
         * The `uri` tag value for exchanges the client recorded no URI template for - and for a recorded
         * "template" without a placeholder: the client records whatever string `uri(String, ...)` was
         * given, so `uri("/things/" + id)` would otherwise put one tag value per id on the meter.
         */
        const val UNTEMPLATED_URI = "UNKNOWN"

        /** The `host` tag value for exchanges whose request URI carries no host. */
        const val UNKNOWN_HOST = "UNKNOWN"

        /**
         * The `name` tag value of the body meters for a client the host did not name ([AdapterName]):
         * `UNNAMED` rather than `UNKNOWN`, because the name is not unknown, it was never given - and the
         * one word an operator filters out to see only the named clients.
         */
        const val UNNAMED_ADAPTER = "UNNAMED"

        /** The `client` tag of the open-exchanges gauge, distinguishing the two twins' gauges in one registry. */
        const val CLIENT_TAG = "client"

        // Both sides weak: the KEY must not pin a host registry that outlives its context, and the VALUE
        // must not pin the owner beyond the registry. The owner lives exactly as long as its registry:
        // the removal listener the registry holds captures the owner, so the reference here is never
        // cleared while the registry is reachable, and a new entry point on a live registry always
        // finds the existing owner (never its own gauge id left behind by a collected one).
        private val perRegistry = WeakHashMap<MeterRegistry, EnumMap<ClientStack, WeakReference<ClientLoggingMetrics>>>()

        /**
         * The metrics owner for [registry] and [stack] - created on first use, SHARED by every later
         * caller with the same registry and stack, so the open-exchanges gauge is the total across the
         * entry points on one registry (the one-instance rule of the class KDoc). Static for the Java
         * caller (the benchmarks).
         */
        @JvmStatic
        fun forRegistry(
            registry: MeterRegistry,
            stack: ClientStack,
        ): ClientLoggingMetrics =
            synchronized(perRegistry) {
                val owners = perRegistry.getOrPut(registry) { EnumMap(ClientStack::class.java) }
                owners[stack]?.get() ?: ClientLoggingMetrics(registry, stack).also { owners[stack] = WeakReference(it) }
            }

        /**
         * The `uri` tag for a recorded template: the template itself when it carries a placeholder,
         * [UNTEMPLATED_URI] otherwise.
         */
        fun uriTag(template: String?): String = template?.takeIf { '{' in it } ?: UNTEMPLATED_URI
    }
}
