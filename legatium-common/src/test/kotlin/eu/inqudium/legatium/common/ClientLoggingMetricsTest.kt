package eu.inqudium.legatium.common

import ch.qos.logback.classic.Level
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.concurrent.atomic.AtomicLong

/**
 * The registration behaviour of the shared metrics owner, driven directly - ONCE here, for both stacks,
 * instead of once per twin through its entry point (architecture review of 2026-09-05, finding 1):
 * pre-registration, the one-owner-per-registry-and-stack rule, the fail-open registration paths, the
 * guarded counter updates and the body meters' cardinality rules. The twins' metrics tests keep the
 * lifecycle facts only their entry point can show (when the gauge moves, what the emitter counts).
 */
class ClientLoggingMetricsTest {
    private fun counters(
        registry: MeterRegistry,
        name: String,
    ): Collection<Counter> = registry.get(name).counters()

    /** A host registry whose counters under [breakingMeters] register fine but throw on every increment. */
    private fun registryWithBreakingCounters(vararg breakingMeters: String): MeterRegistry =
        object : SimpleMeterRegistry() {
            override fun newCounter(id: Meter.Id): Counter {
                val real = super.newCounter(id)
                if (id.name !in breakingMeters) return real
                return object : Counter by real {
                    override fun increment(amount: Double) = error("counter broke")
                }
            }
        }

    /** A host registry whose distribution summaries register fine but throw on every record. */
    private fun registryWithBreakingSummaries(): MeterRegistry =
        object : SimpleMeterRegistry() {
            override fun newDistributionSummary(
                id: Meter.Id,
                distributionStatisticConfig: DistributionStatisticConfig,
                scale: Double,
            ): DistributionSummary {
                val real = super.newDistributionSummary(id, distributionStatisticConfig, scale)
                return object : DistributionSummary by real {
                    override fun record(amount: Double) = error("summary broke")
                }
            }
        }

    /** The response-read count under the one template and host the test records against. */
    private fun responseReadCount(
        registry: MeterRegistry,
        name: String,
        state: String,
    ): Double =
        registry
            .get(ClientLoggingMetrics.RESPONSE_BODY_READ_METER)
            .tags("uri", "https://api.example.com/things/{id}", "host", "api.example.com", "name", name, "state", state)
            .counter()
            .count()

    @ParameterizedTest
    @EnumSource(ClientStack::class)
    internal fun `should pre-register every fixed-tag meter at zero for the stack`(stack: ClientStack) {
        // What is tested: construction against a fresh registry - the fail-open, events and correlation
        //   counters exist for every tag value, and the gauge exists under the stack's client tag, all
        //   at zero, before anything was counted.
        // Success criteria: three fail-open stages, the stack's outcomes (three or four), three sources,
        //   one gauge tagged client=<stack>, every value 0.
        // Why it matters: a rate() alert must see the zero before the first occurrence, not a meter that
        //   springs into existence at the moment it should already fire.
        // Given
        val registry = SimpleMeterRegistry()

        // When
        ClientLoggingMetrics.forRegistry(registry, stack)

        // Then
        assertThat(counters(registry, ClientLoggingMetrics.FAIL_OPEN_METER)).hasSize(3).allSatisfy { assertThat(it.count()).isZero() }
        assertThat(counters(registry, ClientLoggingMetrics.EVENTS_METER).map { it.id.getTag("outcome") })
            .containsExactlyInAnyOrderElementsOf(stack.outcomes.map { it.tagValue })
        assertThat(counters(registry, ClientLoggingMetrics.CORRELATION_METER)).hasSize(3).allSatisfy { assertThat(it.count()).isZero() }
        assertThat(
            registry
                .get(ClientLoggingMetrics.OPEN_EXCHANGES_METER)
                .tag(ClientLoggingMetrics.CLIENT_TAG, stack.tagValue)
                .gauge()
                .value(),
        ).isZero()
    }

    @Test
    fun `should hand out one owner per registry and stack`() {
        // What is tested: forRegistry's cache - same registry and stack give the same instance, a
        //   different stack or a different registry gives a different one.
        // Success criteria: identity for the same key, distinct instances otherwise, and the two stacks'
        //   gauges coexist in one registry under their own client tags.
        // Why it matters: a duplicate owner's gauge registration would be silently ignored by Micrometer
        //   and its open exchanges become invisible; two stacks in one host must NOT share a gauge.
        // Given
        val registry = SimpleMeterRegistry()
        val other = SimpleMeterRegistry()

        // When
        val restclient = ClientLoggingMetrics.forRegistry(registry, ClientStack.RESTCLIENT)
        val webclient = ClientLoggingMetrics.forRegistry(registry, ClientStack.WEBCLIENT)

        // Then
        assertThat(ClientLoggingMetrics.forRegistry(registry, ClientStack.RESTCLIENT)).isSameAs(restclient)
        assertThat(webclient).isNotSameAs(restclient)
        assertThat(ClientLoggingMetrics.forRegistry(other, ClientStack.RESTCLIENT)).isNotSameAs(restclient)
        assertThat(registry.get(ClientLoggingMetrics.OPEN_EXCHANGES_METER).gauges().map { it.id.getTag(ClientLoggingMetrics.CLIENT_TAG) })
            .containsExactlyInAnyOrder("restclient", "webclient")
    }

    @Test
    fun `should move the gauge with opened and completed exchanges`() {
        // What is tested: exchangeOpened/exchangeCompleted against the registered gauge.
        // Success criteria: two opens read 2, one completion reads 1, the second reads 0.
        // Why it matters: the gauge is the one signal for exchanges that never end; it must track the
        //   owner's counter exactly, not a snapshot taken at registration.
        // Given
        val registry = SimpleMeterRegistry()
        val metrics = ClientLoggingMetrics.forRegistry(registry, ClientStack.RESTCLIENT)
        val gauge = registry.get(ClientLoggingMetrics.OPEN_EXCHANGES_METER).gauge()

        // When/Then
        metrics.exchangeOpened()
        metrics.exchangeOpened()
        assertThat(gauge.value()).isEqualTo(2.0)
        metrics.exchangeCompleted()
        assertThat(gauge.value()).isEqualTo(1.0)
        metrics.exchangeCompleted()
        assertThat(gauge.value()).isZero()
    }

    @Test
    fun `should keep working with a private meter when the host registry rejects a registration`() {
        // What is tested: registerOrFallback - the events meter's success id is already taken by a
        //   Gauge, so Micrometer rejects the counter registration with a different-type error.
        // Success criteria: construction succeeds, counting the conflicting outcome does not throw, the
        //   host keeps its gauge and holds no counter under that id, the other outcomes and the fail-open
        //   counters registered normally, and one WARN names the meter as kept private.
        // Why it matters: a name clash with the host or another library must neither abort the context
        //   start nor suppress the exchange event - the one meter goes private, everything else exports.
        // Given: the events meter's success id taken by a gauge, and the metrics logger captured
        val host: MeterRegistry = SimpleMeterRegistry()
        Gauge.builder(ClientLoggingMetrics.EVENTS_METER) { 1.0 }.tag("outcome", "success").register(host)
        val metricsLog = CapturedLogger(ClientLoggingMetrics::class.java.name)
        try {
            // When
            val metrics = ClientLoggingMetrics.forRegistry(host, ClientStack.RESTCLIENT)
            val thrown = catchThrowable { metrics.eventEmitted(ClientOutcome.SUCCESS) }

            // Then
            assertThat(thrown).isNull()
            assertThat(host.find(ClientLoggingMetrics.EVENTS_METER).tag("outcome", "success").gauge()).isNotNull()
            assertThat(host.find(ClientLoggingMetrics.EVENTS_METER).tag("outcome", "success").counter()).isNull()
            assertThat(host.find(ClientLoggingMetrics.EVENTS_METER).tag("outcome", "failure").counter()).isNotNull()
            assertThat(counters(host, ClientLoggingMetrics.FAIL_OPEN_METER)).hasSize(3)
            val warning = metricsLog.events.single()
            assertThat(warning.level).isEqualTo(Level.WARN)
            assertThat(warning.formattedMessage).contains(ClientLoggingMetrics.EVENTS_METER).contains("kept private")
        } finally {
            metricsLog.detach()
        }
    }

    @Test
    fun `should keep the gauge private with a warning when the host registry already holds an identical gauge`() {
        // What is tested: the same-type collision check of the gauge registration - a host (or an older
        //   library copy on another classloader) already registered
        //   adapter.logging.exchanges.open{client=webclient}; Micrometer would return that gauge
        //   unchanged and silently drop this owner's state function.
        // Success criteria: the host's gauge keeps its own value (7) while an exchange is open, the
        //   registry holds exactly one meter under the id, and one WARN names the meter as kept private.
        // Why it matters: without the check the liveness gauge showed a foreign value and this owner's
        //   open exchanges were invisible - the one silent-loss signal itself lost silently.
        // Given: a host gauge under the exact id, and the metrics logger captured
        val host = SimpleMeterRegistry()
        val hostState = AtomicLong(7)
        Gauge
            .builder(ClientLoggingMetrics.OPEN_EXCHANGES_METER, hostState) { it.get().toDouble() }
            .tag(ClientLoggingMetrics.CLIENT_TAG, ClientStack.WEBCLIENT.tagValue)
            .register(host)
        val metricsLog = CapturedLogger(ClientLoggingMetrics::class.java.name)
        try {
            // When
            val metrics = ClientLoggingMetrics.forRegistry(host, ClientStack.WEBCLIENT)
            metrics.exchangeOpened()
            val hostGaugeWhileOpen =
                host
                    .get(ClientLoggingMetrics.OPEN_EXCHANGES_METER)
                    .tag(ClientLoggingMetrics.CLIENT_TAG, ClientStack.WEBCLIENT.tagValue)
                    .gauge()
                    .value()
            metrics.exchangeCompleted()

            // Then
            assertThat(hostGaugeWhileOpen).isEqualTo(7.0)
            assertThat(host.find(ClientLoggingMetrics.OPEN_EXCHANGES_METER).meters()).hasSize(1)
            val warning = metricsLog.events.single()
            assertThat(warning.level).isEqualTo(Level.WARN)
            assertThat(warning.formattedMessage).contains(ClientLoggingMetrics.OPEN_EXCHANGES_METER).contains("kept private")
        } finally {
            metricsLog.detach()
        }
    }

    @Test
    fun `should count a throwing host counter as stage wiring instead of throwing`() {
        // What is tested: updateQuietly around the correlation and events counters - host Counters that
        //   registered fine but throw on increment.
        // Success criteria: neither requestId nor eventEmitted throws, and the fail-open counter shows
        //   stage=wiring at exactly 2 on the hostile registry.
        // Why it matters: a bookkeeping failure in a host meter must degrade to a lost count, never
        //   surface in the entry point and turn the call into an unlogged pass-through.
        // Given: a registry whose correlation and events counters throw on increment
        val hostile = registryWithBreakingCounters(ClientLoggingMetrics.CORRELATION_METER, ClientLoggingMetrics.EVENTS_METER)
        val metrics = ClientLoggingMetrics.forRegistry(hostile, ClientStack.RESTCLIENT)

        // When
        val thrown =
            catchThrowable {
                metrics.requestId(RequestIdSource.GENERATED)
                metrics.eventEmitted(ClientOutcome.SUCCESS)
            }

        // Then
        assertThat(thrown).isNull()
        assertThat(
            hostile
                .get(ClientLoggingMetrics.FAIL_OPEN_METER)
                .tags("stage", "wiring")
                .counter()
                .count(),
        ).isEqualTo(2.0)
    }

    @Test
    fun `should warn once per meter for a permanently throwing host counter and keep counting every failure`() {
        // What is tested: the warning throttle in updateQuietly - a host counter that throws on EVERY
        //   increment is hit twice per exchange (request-id origin and events).
        // Success criteria: after three exchanges' worth of updates the fail-open counter shows
        //   stage=wiring at 6, but the module logger carries exactly ONE warning for the meter.
        // Why it matters: a warning per hit would flood the internal logger proportionally to the
        //   traffic and drown the curated one-time warnings; the counter is the measure of the loss.
        // Given: a registry whose correlation counter always throws, and the module logger captured
        val hostile = registryWithBreakingCounters(ClientLoggingMetrics.CORRELATION_METER)
        val moduleLog = CapturedLogger(ClientLoggingMetrics::class.java.name)
        try {
            val metrics = ClientLoggingMetrics.forRegistry(hostile, ClientStack.WEBCLIENT)

            // When
            repeat(6) { metrics.requestId(RequestIdSource.TRACE) }

            // Then
            assertThat(
                hostile
                    .get(ClientLoggingMetrics.FAIL_OPEN_METER)
                    .tags("stage", "wiring")
                    .counter()
                    .count(),
            ).isEqualTo(6.0)
            assertThat(moduleLog.events.filter { it.level == Level.WARN && it.formattedMessage.contains("could not be updated") }).hasSize(1)
        } finally {
            moduleLog.detach()
        }
    }

    @Test
    fun `should register a body meter anew after the host removed it instead of recording into the detached one`() {
        // What is tested: the cache's one drift case - the owner resolves the body meters once per tag
        //   set, and a host may remove a meter from its registry afterwards.
        // Success criteria: after the removal the next sample lands in a NEW summary the registry holds
        //   (count 1, the new amount), the removed instance saw nothing more; the same for the
        //   read-state counter.
        // Why it matters: a cached reference to a removed meter would count every later exchange into
        //   an instance no exporter reads - silent loss of exactly the opt-in measurement.
        // Given: a sample and a read state recorded, both meters then removed by the host
        val registry = SimpleMeterRegistry()
        val metrics = ClientLoggingMetrics.forRegistry(registry, ClientStack.RESTCLIENT)
        metrics.requestBodySize("https://api.example.com/things/{id}", "api.example.com", "things", 5)
        metrics.responseBodyRead("https://api.example.com/things/{id}", "api.example.com", "things", BodyReadState.COMPLETE)
        val removedSummary = registry.get(ClientLoggingMetrics.REQUEST_BODY_SIZE_METER).summary()
        val removedCounter = registry.get(ClientLoggingMetrics.RESPONSE_BODY_READ_METER).counter()
        registry.remove(removedSummary)
        registry.remove(removedCounter)

        // When
        metrics.requestBodySize("https://api.example.com/things/{id}", "api.example.com", "things", 7)
        metrics.responseBodyRead("https://api.example.com/things/{id}", "api.example.com", "things", BodyReadState.COMPLETE)

        // Then
        val summary = registry.get(ClientLoggingMetrics.REQUEST_BODY_SIZE_METER).summary()
        assertThat(summary).isNotSameAs(removedSummary)
        assertThat(summary.count()).isEqualTo(1)
        assertThat(summary.totalAmount()).isEqualTo(7.0)
        assertThat(removedSummary.count()).isEqualTo(1)
        val counter = registry.get(ClientLoggingMetrics.RESPONSE_BODY_READ_METER).counter()
        assertThat(counter).isNotSameAs(removedCounter)
        assertThat(counter.count()).isEqualTo(1.0)
        assertThat(removedCounter.count()).isEqualTo(1.0)
    }

    @Test
    fun `should keep a body meter private with one warning when the host rejects its registration`() {
        // What is tested: registerOrFallback behind the body-meter cache - the request summary's id is
        //   taken by a host gauge, so Micrometer rejects the registration with a different-type error.
        // Success criteria: three samples neither throw nor reach the host (which keeps its gauge and
        //   holds no summary under the id); the module logger carries exactly one WARN naming the meter.
        // Why it matters: the conflict path of the fixed meters is pinned by the events-meter test; the
        //   body meters take it lazily through the cache, and must take it once, not per exchange.
        // Given: the summary's exact id taken by a gauge, and the metrics logger captured
        val host: MeterRegistry = SimpleMeterRegistry()
        Gauge
            .builder(ClientLoggingMetrics.REQUEST_BODY_SIZE_METER) { 1.0 }
            .tags("uri", "https://api.example.com/things/{id}", "host", "api.example.com", "name", "things")
            .register(host)
        val metricsLog = CapturedLogger(ClientLoggingMetrics::class.java.name)
        try {
            val metrics = ClientLoggingMetrics.forRegistry(host, ClientStack.RESTCLIENT)

            // When
            val thrown = catchThrowable { repeat(3) { metrics.requestBodySize("https://api.example.com/things/{id}", "api.example.com", "things", 5) } }

            // Then
            assertThat(thrown).isNull()
            assertThat(host.find(ClientLoggingMetrics.REQUEST_BODY_SIZE_METER).gauge()).isNotNull()
            assertThat(host.find(ClientLoggingMetrics.REQUEST_BODY_SIZE_METER).summary()).isNull()
            val warnings = metricsLog.events.filter { it.level == Level.WARN }
            assertThat(warnings).hasSize(1)
            assertThat(warnings.single().formattedMessage).contains(ClientLoggingMetrics.REQUEST_BODY_SIZE_METER).contains("kept private")
        } finally {
            metricsLog.detach()
        }
    }

    @Test
    fun `should count a throwing host body summary per hit and warn once, like the fixed counters`() {
        // What is tested: updateQuietly around the dynamic body meters - a host DistributionSummary
        //   that registered fine but throws on every record.
        // Success criteria: six samples neither throw nor reach the caller; the fail-open counter shows
        //   stage=wiring at 6 and the module logger carries exactly ONE warning for the meter.
        // Why it matters: the body meters are recorded per measured exchange; before they shared the
        //   throttle, a permanently throwing host summary produced a warning per exchange in the
        //   twins' emitters, proportional to the traffic.
        // Given: a registry whose summaries always throw, and the module logger captured
        val hostile = registryWithBreakingSummaries()
        val moduleLog = CapturedLogger(ClientLoggingMetrics::class.java.name)
        try {
            val metrics = ClientLoggingMetrics.forRegistry(hostile, ClientStack.RESTCLIENT)

            // When
            val thrown = catchThrowable { repeat(6) { metrics.requestBodySize("https://api.example.com/things/{id}", "api.example.com", null, 5) } }

            // Then
            assertThat(thrown).isNull()
            assertThat(
                hostile
                    .get(ClientLoggingMetrics.FAIL_OPEN_METER)
                    .tags("stage", "wiring")
                    .counter()
                    .count(),
            ).isEqualTo(6.0)
            assertThat(moduleLog.events.filter { it.level == Level.WARN && it.formattedMessage.contains("could not be updated") }).hasSize(1)
        } finally {
            moduleLog.detach()
        }
    }

    @Test
    fun `should fold a recorded template without a placeholder into the untemplated tag value`() {
        // What is tested: the cardinality guard of the body meters' `uri` tag - the client records
        //   whatever string was passed to uri(String, ...), so `uri("/things/" + id)` would put one tag
        //   value per id on the meter.
        // Success criteria: a template with a placeholder is kept; one without, or none, folds to UNKNOWN.
        // Why it matters: an unbounded tag set is a slow memory leak in the host registry.
        // Given/When/Then
        assertThat(ClientLoggingMetrics.uriTag("https://api.example.com/things/{id}")).isEqualTo("https://api.example.com/things/{id}")
        assertThat(ClientLoggingMetrics.uriTag("https://api.example.com/things/42")).isEqualTo(ClientLoggingMetrics.UNTEMPLATED_URI)
        assertThat(ClientLoggingMetrics.uriTag(null)).isEqualTo(ClientLoggingMetrics.UNTEMPLATED_URI)
    }

    @Test
    fun `should record body sizes under template and host and skip zero-byte bodies`() {
        // What is tested: the lazily created size summaries - the tag set, the base unit, the
        //   host fallback, and the zero-byte rule.
        // Success criteria: two request samples (5 and 7 bytes) land in ONE summary tagged by template
        //   and host; a body under an unknown host tags UNKNOWN; a zero-byte body creates no summary.
        // Why it matters: the summary describes bodies that exist and the sum stays exact either way; a
        //   summary per call site is what the cardinality rules promise the host registry.
        // Given
        val registry = SimpleMeterRegistry()
        val metrics = ClientLoggingMetrics.forRegistry(registry, ClientStack.RESTCLIENT)

        // When
        metrics.requestBodySize("https://api.example.com/things/{id}", "api.example.com", "things", 5)
        metrics.requestBodySize("https://api.example.com/things/{id}", "api.example.com", "things", 7)
        metrics.responseBodySize(null, null, null, 3)
        metrics.responseBodySize("https://api.example.com/other/{id}", "api.example.com", "things", 0)

        // Then
        val request =
            registry
                .get(ClientLoggingMetrics.REQUEST_BODY_SIZE_METER)
                .tags("uri", "https://api.example.com/things/{id}", "host", "api.example.com", "name", "things")
                .summary()
        assertThat(request.count()).isEqualTo(2)
        assertThat(request.totalAmount()).isEqualTo(12.0)
        assertThat(request.id.baseUnit).isEqualTo("bytes")
        assertThat(
            registry
                .get(ClientLoggingMetrics.RESPONSE_BODY_SIZE_METER)
                .tags("uri", ClientLoggingMetrics.UNTEMPLATED_URI, "host", ClientLoggingMetrics.UNKNOWN_HOST, "name", ClientLoggingMetrics.UNNAMED_ADAPTER)
                .summary()
                .totalAmount(),
        ).isEqualTo(3.0)
        assertThat(registry.find(ClientLoggingMetrics.RESPONSE_BODY_SIZE_METER).tag("uri", "https://api.example.com/other/{id}").summary()).isNull()
    }

    @Test
    fun `should count the response read state per template, host, name and state`() {
        // What is tested: responseBodyRead - the lazily created counter and its four-tag id.
        // Success criteria: two unread and one complete recording under one template/host/name give
        //   counters of 2 and 1; no partial counter exists; a recording without a name lands under
        //   name=UNNAMED, not under the named client.
        // Why it matters: the unread share per call site is the one place a discarded payload is visible,
        //   and behind a sidecar the name is the tag that keeps the call sites of several clients apart.
        // Given
        val registry = SimpleMeterRegistry()
        val metrics = ClientLoggingMetrics.forRegistry(registry, ClientStack.WEBCLIENT)

        // When
        metrics.responseBodyRead("https://api.example.com/things/{id}", "api.example.com", "things", BodyReadState.UNREAD)
        metrics.responseBodyRead("https://api.example.com/things/{id}", "api.example.com", "things", BodyReadState.UNREAD)
        metrics.responseBodyRead("https://api.example.com/things/{id}", "api.example.com", "things", BodyReadState.COMPLETE)
        metrics.responseBodyRead("https://api.example.com/things/{id}", "api.example.com", null, BodyReadState.COMPLETE)

        // Then
        assertThat(responseReadCount(registry, "things", "unread")).isEqualTo(2.0)
        assertThat(responseReadCount(registry, "things", "complete")).isEqualTo(1.0)
        assertThat(responseReadCount(registry, ClientLoggingMetrics.UNNAMED_ADAPTER, "complete")).isEqualTo(1.0)
        assertThat(registry.find(ClientLoggingMetrics.RESPONSE_BODY_READ_METER).tag("state", "partial").counter()).isNull()
    }

    @Test
    fun `should be a no-op against an empty composite registry`() {
        // What is tested: the owner against the registry the auto-configurations hand it when the host
        //   has none - an empty CompositeMeterRegistry, whose meters are Micrometer no-ops.
        // Success criteria: construction and every recording path succeed, nothing is warned, and the
        //   composite holds the registered ids but no child accumulates a value.
        // Why it matters: a host without actuator must run the module unchanged - with no private
        //   registry quietly accumulating per-template meters nobody ever reads.
        // Given
        val empty = CompositeMeterRegistry()
        val metricsLog = CapturedLogger(ClientLoggingMetrics::class.java.name)
        try {
            // When
            val metrics = ClientLoggingMetrics.forRegistry(empty, ClientStack.RESTCLIENT)
            val thrown =
                catchThrowable {
                    metrics.exchangeOpened()
                    metrics.requestId(RequestIdSource.TRACE)
                    metrics.eventEmitted(ClientOutcome.SUCCESS)
                    metrics.requestBodySize("https://api.example.com/things/{id}", "api.example.com", null, 5)
                    metrics.responseBodyRead("https://api.example.com/things/{id}", "api.example.com", null, BodyReadState.COMPLETE)
                    metrics.emissionFailure()
                    metrics.exchangeCompleted()
                }

            // Then
            assertThat(thrown).isNull()
            assertThat(metricsLog.events).isEmpty()
            assertThat(empty.registries).isEmpty()
            assertThat(
                empty
                    .get(ClientLoggingMetrics.EVENTS_METER)
                    .tag("outcome", "success")
                    .counter()
                    .count(),
            ).isZero()
        } finally {
            metricsLog.detach()
        }
    }
}
