package eu.inqudium.legatium.common

import ch.qos.logback.classic.Level
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
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
                .tag(ClientLoggingMetrics.CLIENT_TAG, stack.tag)
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
            .tag(ClientLoggingMetrics.CLIENT_TAG, ClientStack.WEBCLIENT.tag)
            .register(host)
        val metricsLog = CapturedLogger(ClientLoggingMetrics::class.java.name)
        try {
            // When
            val metrics = ClientLoggingMetrics.forRegistry(host, ClientStack.WEBCLIENT)
            metrics.exchangeOpened()
            val hostGaugeWhileOpen =
                host
                    .get(ClientLoggingMetrics.OPEN_EXCHANGES_METER)
                    .tag(ClientLoggingMetrics.CLIENT_TAG, ClientStack.WEBCLIENT.tag)
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
        val hostile: MeterRegistry =
            object : SimpleMeterRegistry() {
                override fun newCounter(id: Meter.Id): Counter {
                    val real = super.newCounter(id)
                    if (id.name != ClientLoggingMetrics.CORRELATION_METER && id.name != ClientLoggingMetrics.EVENTS_METER) return real
                    return object : Counter by real {
                        override fun increment(amount: Double) = throw IllegalStateException("counter broke")
                    }
                }
            }
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
        metrics.requestBodySize("https://api.example.com/things/{id}", "api.example.com", 5)
        metrics.requestBodySize("https://api.example.com/things/{id}", "api.example.com", 7)
        metrics.responseBodySize(null, null, 3)
        metrics.responseBodySize("https://api.example.com/other/{id}", "api.example.com", 0)

        // Then
        val request =
            registry
                .get(ClientLoggingMetrics.REQUEST_BODY_SIZE_METER)
                .tags("uri", "https://api.example.com/things/{id}", "host", "api.example.com")
                .summary()
        assertThat(request.count()).isEqualTo(2)
        assertThat(request.totalAmount()).isEqualTo(12.0)
        assertThat(request.id.baseUnit).isEqualTo("bytes")
        assertThat(
            registry
                .get(ClientLoggingMetrics.RESPONSE_BODY_SIZE_METER)
                .tags("uri", ClientLoggingMetrics.UNTEMPLATED_URI, "host", ClientLoggingMetrics.UNKNOWN_HOST)
                .summary()
                .totalAmount(),
        ).isEqualTo(3.0)
        assertThat(registry.find(ClientLoggingMetrics.RESPONSE_BODY_SIZE_METER).tag("uri", "https://api.example.com/other/{id}").summary()).isNull()
    }

    @Test
    fun `should count the response read state per template, host and state`() {
        // What is tested: responseBodyRead - the lazily created counter and its three-tag id.
        // Success criteria: two unread and one complete recording under one template/host give
        //   counters of 2 and 1; no partial counter exists.
        // Why it matters: the unread share per call site is the one place a discarded payload is visible.
        // Given
        val registry = SimpleMeterRegistry()
        val metrics = ClientLoggingMetrics.forRegistry(registry, ClientStack.WEBCLIENT)

        // When
        metrics.responseBodyRead("https://api.example.com/things/{id}", "api.example.com", BodyReadState.UNREAD)
        metrics.responseBodyRead("https://api.example.com/things/{id}", "api.example.com", BodyReadState.UNREAD)
        metrics.responseBodyRead("https://api.example.com/things/{id}", "api.example.com", BodyReadState.COMPLETE)

        // Then
        fun read(state: String) =
            registry
                .get(ClientLoggingMetrics.RESPONSE_BODY_READ_METER)
                .tags("uri", "https://api.example.com/things/{id}", "host", "api.example.com", "state", state)
                .counter()
                .count()
        assertThat(read("unread")).isEqualTo(2.0)
        assertThat(read("complete")).isEqualTo(1.0)
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
                    metrics.requestBodySize("https://api.example.com/things/{id}", "api.example.com", 5)
                    metrics.responseBodyRead("https://api.example.com/things/{id}", "api.example.com", BodyReadState.COMPLETE)
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
