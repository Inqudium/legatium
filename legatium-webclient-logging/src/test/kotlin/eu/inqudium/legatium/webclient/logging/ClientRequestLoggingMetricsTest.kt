package eu.inqudium.legatium.webclient.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.CorrelationIdGenerator
import eu.inqudium.legatium.common.NanoTimeSource
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeFunction
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.io.IOException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * The six meters of [ClientLoggingMetrics] as driven by the filter: pre-registration with the reactive
 * outcome vocabulary, the events counter per outcome, the open-exchanges gauge across the body's
 * lifetime, the request-id source counter, the body meters, the fail-open stages, and the
 * one-owner-per-registry rule.
 */
class ClientRequestLoggingMetricsTest {
    private val ticker = AtomicLong(0)
    private val registry = SimpleMeterRegistry()
    private val properties = ClientLoggingProperties(loggerName = "http-adapter-exchange-reactive-metrics-test")
    private val filter = ClientRequestLoggingFilter(properties, { ticker.get() }, { "generated-42" }, registry)
    private lateinit var log: CapturedLogger

    @BeforeEach
    fun setUp() {
        log = CapturedLogger(properties.loggerName)
    }

    @AfterEach
    fun tearDown() {
        log.detach()
    }

    private fun counter(
        name: String,
        vararg tags: String,
    ): Double =
        registry
            .get(name)
            .tags(*tags)
            .counter()
            .count()

    private fun gauge(): Double = registry.get(ClientLoggingMetrics.OPEN_EXCHANGES_METER).gauge().value()

    private fun ClientRequestLoggingFilter.call(
        request: ClientRequest,
        next: ExchangeFunction,
    ): String? = filter(request, next).flatMap { it.bodyToMono(String::class.java) }.block()

    @Nested
    inner class `Counters and gauge` {
        @Test
        fun `should pre-register the reactive outcome vocabulary and count emitted events per outcome`() {
            // What is tested: every fixed-tag meter exists at zero before the first call - including the
            //   cancelled outcome the blocking twin does not have - and the events counter counts by
            //   outcome.
            // Success criteria: four outcomes at zero; one call each moves its side.
            // Why it matters: a rate() alert must see the zero before the first occurrence.
            // Given
            listOf("success", "failure", "timeout", "cancelled").forEach {
                assertThat(counter(ClientLoggingMetrics.EVENTS_METER, "outcome", it)).isZero()
            }

            // When
            filter.call(request(), answering())
            filter.call(request(), answering(status = HttpStatus.BAD_GATEWAY))
            StepVerifier.create(filter.filter(request(), ExchangeFunction { Mono.error(TimeoutException("t")) })).expectError().verify()
            StepVerifier.create(filter.filter(request(), ExchangeFunction { Mono.never() })).thenCancel().verify()

            // Then
            listOf("success", "failure", "timeout", "cancelled").forEach {
                assertThat(counter(ClientLoggingMetrics.EVENTS_METER, "outcome", it)).describedAs(it).isEqualTo(1.0)
            }
        }

        @Test
        fun `should keep the open-exchanges gauge up until the body's terminal signal`() {
            // What is tested: the gauge as the liveness signal of the body-driven emission.
            // Success criteria: 1 after the response was delivered but before the body was consumed,
            //   0 after; a failed call goes up and down within the signal.
            // Why it matters: a body nobody consumes must stay VISIBLE - the gauge baseline is the only
            //   signal for that silent-loss mode.
            // Given/When
            val response = filter.filter(request(), answering(body = "x")).block()!!
            assertThat(gauge()).isEqualTo(1.0)
            response.releaseBody().block()

            // Then
            assertThat(gauge()).isZero()
            StepVerifier.create(filter.filter(request(), ExchangeFunction { Mono.error(IOException("refused")) })).expectError().verify()
            assertThat(gauge()).isZero()
        }

        @Test
        fun `should count the request-id origin per source`() {
            // Given/When
            filter.call(request { header("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01") }, answering())
            filter.call(request { header("X-Correlation-Id", "c-1") }, answering())
            filter.call(request(), answering())

            // Then
            assertThat(counter(ClientLoggingMetrics.CORRELATION_METER, "source", "trace")).isEqualTo(1.0)
            assertThat(counter(ClientLoggingMetrics.CORRELATION_METER, "source", "header")).isEqualTo(1.0)
            assertThat(counter(ClientLoggingMetrics.CORRELATION_METER, "source", "generated")).isEqualTo(1.0)
        }

        @Test
        fun `should share one metrics owner between two filters on the same registry`() {
            // Given
            val second = ClientRequestLoggingFilter(properties, { ticker.get() }, { "generated-43" }, registry)

            // When/Then
            val response = second.filter(request(), answering()).block()!!
            assertThat(gauge()).isEqualTo(1.0)
            response.releaseBody().block()
            assertThat(gauge()).isZero()
        }
    }

    @Nested
    inner class `Body meters` {
        @Test
        fun `should record body sizes and the response read state under template and host, independent of the level gate`() {
            // Given
            val measuring =
                ClientRequestLoggingFilter(
                    properties.copy(measureRequestBodySize = true, measureResponseBodySize = true),
                    { ticker.get() },
                    { "generated-42" },
                    registry,
                )
            log.logger.level = Level.OFF
            val request =
                request(method = HttpMethod.POST, uri = "https://api.example.com/things/7") {
                    attribute(ClientRequestLoggingFilter.URI_TEMPLATE_ATTRIBUTE, "https://api.example.com/things/{id}")
                    body(
                        org.springframework.web.reactive.function.BodyInserters
                            .fromValue("hello"),
                    )
                }
            val writing =
                ExchangeFunction { req ->
                    val connectorRequest =
                        org.springframework.mock.http.client.reactive
                            .MockClientHttpRequest(req.method(), req.url())
                    req
                        .writeTo(
                            connectorRequest,
                            org.springframework.web.reactive.function.client.ExchangeStrategies
                                .withDefaults(),
                        ).then(
                            answering(body = "world!").exchange(req),
                        )
                }

            // When
            measuring.call(request, writing)

            // Then
            val tags = arrayOf("uri", "https://api.example.com/things/{id}", "host", "api.example.com")
            assertThat(
                registry
                    .get(ClientLoggingMetrics.REQUEST_BODY_SIZE_METER)
                    .tags(*tags)
                    .summary()
                    .totalAmount(),
            ).isEqualTo(5.0)
            assertThat(
                registry
                    .get(ClientLoggingMetrics.RESPONSE_BODY_SIZE_METER)
                    .tags(*tags)
                    .summary()
                    .totalAmount(),
            ).isEqualTo(6.0)
            assertThat(counter(ClientLoggingMetrics.RESPONSE_BODY_READ_METER, *tags, "state", "complete")).isEqualTo(1.0)
            assertThat(log.events).isEmpty()
        }

        @Test
        fun `should not record a read state when the call produced no response`() {
            // Given
            val measuring = ClientRequestLoggingFilter(properties.copy(measureResponseBodySize = true), { ticker.get() }, { "generated-42" }, registry)

            // When
            StepVerifier.create(measuring.filter(request(), ExchangeFunction { Mono.error(IOException("refused")) })).expectError().verify()

            // Then
            assertThat(registry.find(ClientLoggingMetrics.RESPONSE_BODY_READ_METER).counter()).isNull()
        }
    }

    @Nested
    inner class `Fail-open stages` {
        @Test
        fun `should degrade to a pass-through and count stage wiring when the id generator throws`() {
            // Given
            val broken = ClientRequestLoggingFilter(properties, { ticker.get() }, { throw IllegalStateException("no ids") }, registry)

            // When
            val body = broken.call(request(), answering(body = "served"))

            // Then
            assertThat(body).isEqualTo("served")
            assertThat(log.events).isEmpty()
            assertThat(counter(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "wiring")).isEqualTo(1.0)
            assertThat(gauge()).isZero()
        }

        @Test
        fun `should count a broken emission as stage emission and never disturb the body`() {
            // What is tested: the emission guard - the injected time source throws at emission time,
            //   inside the body's terminal callback.
            // Success criteria: the body completes normally for the caller, no event, emission=1.
            // Why it matters: the emission counter is the metric channel for exactly this loss, and the
            //   callback runs inside Reactor's signal propagation.
            // Given
            val reads = AtomicLong(0)
            val flaky =
                ClientRequestLoggingFilter(
                    properties,
                    NanoTimeSource { if (reads.incrementAndGet() == 1L) 0L else throw IllegalStateException("clock broke") },
                    CorrelationIdGenerator { "generated-42" },
                    registry,
                )

            // When
            val body = flaky.call(request(), answering(body = "served"))

            // Then
            assertThat(body).isEqualTo("served")
            assertThat(log.events).isEmpty()
            assertThat(counter(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "emission")).isEqualTo(1.0)
            assertThat(gauge()).isZero()
        }

        @Test
        fun `should confine an arrival-line backend failure and count stage arrival`() {
            // Given: a logging backend whose level check throws for the exchange logger at INFO
            val context = LoggerFactory.getILoggerFactory() as LoggerContext
            val exchangeLogger = properties.loggerName
            var armed = true
            val throwing =
                object : TurboFilter() {
                    override fun decide(
                        marker: Marker?,
                        logger: Logger,
                        level: Level,
                        format: String?,
                        params: Array<Any>?,
                        t: Throwable?,
                    ): FilterReply {
                        if (armed && logger.name == exchangeLogger && level == Level.INFO) throw IllegalStateException("backend broke")
                        return FilterReply.NEUTRAL
                    }
                }
            context.addTurboFilter(throwing)
            try {
                val announcing = ClientRequestLoggingFilter(properties.copy(logRequestStart = true), { ticker.get() }, { "generated-42" }, registry)

                // When
                val response = announcing.filter(request(), answering(body = "served")).block()!!
                armed = false
                val body = response.bodyToMono(String::class.java).block()

                // Then
                assertThat(body).isEqualTo("served")
                assertThat(counter(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "arrival")).isEqualTo(1.0)
                assertThat(log.events).hasSize(1)
            } finally {
                context.turboFilterList.remove(throwing)
            }
        }

        @Test
        fun `should keep working with a private meter when the host registry rejects a registration`() {
            // Given: the events meter's success id taken by a gauge
            val conflicting: MeterRegistry = SimpleMeterRegistry()
            Gauge.builder(ClientLoggingMetrics.EVENTS_METER) { 1.0 }.tag("outcome", "success").register(conflicting)

            // When
            val onConflict = ClientRequestLoggingFilter(properties, { ticker.get() }, { "generated-42" }, conflicting)
            onConflict.call(request(), answering())

            // Then
            assertThat(log.events).hasSize(1)
            assertThat(conflicting.find(ClientLoggingMetrics.EVENTS_METER).tag("outcome", "success").counter()).isNull()
            assertThat(conflicting.find(ClientLoggingMetrics.EVENTS_METER).tag("outcome", "failure").counter()).isNotNull()
        }

        @Test
        fun `should count a throwing host counter as stage wiring and still log the exchange`() {
            // Given
            val hostile: MeterRegistry =
                object : SimpleMeterRegistry() {
                    override fun newCounter(id: Meter.Id): Counter {
                        val real = super.newCounter(id)
                        if (id.name != ClientLoggingMetrics.CORRELATION_METER) return real
                        return object : Counter by real {
                            override fun increment(amount: Double) = throw IllegalStateException("counter broke")
                        }
                    }
                }
            val onHostile = ClientRequestLoggingFilter(properties, { ticker.get() }, { "generated-42" }, hostile)

            // When
            onHostile.call(request(), answering())

            // Then
            assertThat(log.events).hasSize(1)
            assertThat(
                hostile
                    .get(ClientLoggingMetrics.FAIL_OPEN_METER)
                    .tags("stage", "wiring")
                    .counter()
                    .count(),
            ).isEqualTo(1.0)
        }

        @Test
        fun `should confine a terminal-callback failure and still complete the exchange`() {
            // What is tested: the completion guard - the gauge decrement is a host-registry call made
            //   inside Reactor's signal propagation.
            // Success criteria: with a registry whose gauge bookkeeping cannot fail but whose events
            //   counter throws, the body completes for the caller, the event is emitted, wiring counted.
            // Why it matters: an escaping exception there would be rethrown into the caller's pipeline.
            // Given: a registry whose events counter throws on increment
            val hostile: MeterRegistry =
                object : SimpleMeterRegistry() {
                    override fun newCounter(id: Meter.Id): Counter {
                        val real = super.newCounter(id)
                        if (id.name != ClientLoggingMetrics.EVENTS_METER) return real
                        return object : Counter by real {
                            override fun increment(amount: Double) = throw IllegalStateException("events broke")
                        }
                    }
                }
            val onHostile = ClientRequestLoggingFilter(properties, { ticker.get() }, { "generated-42" }, hostile)

            // When
            val thrown = catchThrowable { onHostile.call(request(), answering(body = "served")) }

            // Then
            assertThat(thrown).isNull()
            assertThat(log.events).hasSize(1)
            assertThat(
                hostile
                    .get(ClientLoggingMetrics.FAIL_OPEN_METER)
                    .tags("stage", "wiring")
                    .counter()
                    .count(),
            ).isEqualTo(1.0)
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
    fun `should register the open-exchanges gauge under this twin's client tag`() {
        // What is tested: the gauge id carries a `client` tag naming the twin.
        // Success criteria: the gauge is found under client=webclient.
        // Why it matters: Micrometer deduplicates by id and keeps the FIRST gauge registered under a
        //   bare name - in a host carrying both twins the second twin's open exchanges would vanish.
        // Given/When/Then
        assertThat(
            registry
                .get(ClientLoggingMetrics.OPEN_EXCHANGES_METER)
                .tag(ClientLoggingMetrics.CLIENT_TAG, "webclient")
                .gauge()
                .value(),
        ).isZero()
    }
}
