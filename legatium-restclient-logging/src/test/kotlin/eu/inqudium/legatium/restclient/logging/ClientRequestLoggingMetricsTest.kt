package eu.inqudium.legatium.restclient.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import eu.inqudium.legatium.common.BodyReadState
import eu.inqudium.legatium.common.ClientLoggingMetrics
import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.CorrelationIdGenerator
import eu.inqudium.legatium.common.NanoTimeSource
import io.micrometer.core.instrument.Counter
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
import org.springframework.http.HttpStatus
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * The six meters of [ClientLoggingMetrics] as driven by the interceptor: pre-registration, the events
 * counter per outcome, the open-exchanges gauge across the response's lifetime, the request-id source
 * counter, the body meters, the fail-open stages, and the one-owner-per-registry rule.
 */
class ClientRequestLoggingMetricsTest {
    private val ticker = AtomicLong(0)
    private val registry = SimpleMeterRegistry()
    private val properties = ClientLoggingProperties(loggerName = "http-adapter-exchange-metrics-test")
    private val interceptor = ClientRequestLoggingInterceptor(properties, { ticker.get() }, { "generated-42" }, registry)
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

    @Nested
    inner class `Counters and gauge` {
        @Test
        fun `should pre-register the outcome vocabulary and count emitted events per outcome`() {
            // What is tested: every fixed-tag meter exists at zero before the first call, and the events
            //   counter counts emitted events by outcome.
            // Success criteria: success/failure/timeout exist at zero; one call each moves its side.
            // Why it matters: a rate() alert must see the zero before the first occurrence.
            // Given: nothing happened yet
            assertThat(counter(ClientLoggingMetrics.EVENTS_METER, "outcome", "success")).isZero()
            assertThat(counter(ClientLoggingMetrics.EVENTS_METER, "outcome", "failure")).isZero()
            assertThat(counter(ClientLoggingMetrics.EVENTS_METER, "outcome", "timeout")).isZero()
            assertThat(registry.get(ClientLoggingMetrics.FAIL_OPEN_METER).counters()).hasSize(3)
            assertThat(registry.get(ClientLoggingMetrics.CORRELATION_METER).counters()).hasSize(3)

            // When: a success, a 5xx, a timeout
            interceptor.intercept(request(), ByteArray(0), answering()).consumeAndClose()
            interceptor.intercept(request(), ByteArray(0), answering(status = HttpStatus.BAD_GATEWAY)).consumeAndClose()
            catchThrowable { interceptor.intercept(request(), ByteArray(0)) { _, _ -> throw SocketTimeoutException("read") } }

            // Then
            assertThat(counter(ClientLoggingMetrics.EVENTS_METER, "outcome", "success")).isEqualTo(1.0)
            assertThat(counter(ClientLoggingMetrics.EVENTS_METER, "outcome", "failure")).isEqualTo(1.0)
            assertThat(counter(ClientLoggingMetrics.EVENTS_METER, "outcome", "timeout")).isEqualTo(1.0)
        }

        @Test
        fun `should keep the open-exchanges gauge up until the response is closed`() {
            // What is tested: the gauge as the liveness signal of the close-based emission.
            // Success criteria: 1 while the response is open (body unread or read), 0 after close; a
            //   failed call goes up and down within the call.
            // Why it matters: a response that is never closed must stay VISIBLE - the gauge baseline is
            //   the only signal for that silent-loss mode.
            // Given/When
            val response = interceptor.intercept(request(), ByteArray(0), answering(body = "x"))
            assertThat(gauge()).isEqualTo(1.0)
            response.body.readAllBytes()
            assertThat(gauge()).isEqualTo(1.0)
            response.close()

            // Then
            assertThat(gauge()).isZero()
            catchThrowable { interceptor.intercept(request(), ByteArray(0)) { _, _ -> throw IOException("refused") } }
            assertThat(gauge()).isZero()
        }

        @Test
        fun `should count the request-id origin per source`() {
            // What is tested: metrics.requestId with the source ClientIdentity.resolve decides - a
            //   conformant traceparent counts as trace, an acceptable correlation header as header,
            //   neither as generated.
            // Success criteria: after one call of each kind the adapter.logging.correlation.id
            //   counter holds exactly 1.0 under each of the three source tags.
            // Why it matters: a rising generated share is the only signal that the application
            //   stopped propagating its trace or correlation header onto outbound calls.
            // Given/When: a traced, a header-carrying and a bare request
            interceptor
                .intercept(request().apply { headers.set("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01") }, ByteArray(0), answering())
                .consumeAndClose()
            interceptor.intercept(request().apply { headers.set("X-Correlation-Id", "c-1") }, ByteArray(0), answering()).consumeAndClose()
            interceptor.intercept(request(), ByteArray(0), answering()).consumeAndClose()

            // Then
            assertThat(counter(ClientLoggingMetrics.CORRELATION_METER, "source", "trace")).isEqualTo(1.0)
            assertThat(counter(ClientLoggingMetrics.CORRELATION_METER, "source", "header")).isEqualTo(1.0)
            assertThat(counter(ClientLoggingMetrics.CORRELATION_METER, "source", "generated")).isEqualTo(1.0)
        }

        @Test
        fun `should share one metrics owner between two interceptors on the same registry`() {
            // What is tested: the per-registry ownership - a second interceptor against the SAME
            //   registry observes through the shared owner, not through a duplicate whose gauge
            //   registration Micrometer would silently ignore.
            // Success criteria: an exchange handled by the SECOND interceptor moves the registry's
            //   gauge to 1 mid-flight and back to 0 at close.
            // Why it matters: with a duplicate owner the second interceptor's live calls were invisible.
            // Given
            val second = ClientRequestLoggingInterceptor(properties, { ticker.get() }, { "generated-43" }, registry)

            // When/Then
            val response = second.intercept(request(), ByteArray(0), answering())
            assertThat(gauge()).isEqualTo(1.0)
            response.close()
            assertThat(gauge()).isZero()
        }
    }

    @Nested
    inner class `Body meters` {
        @Test
        fun `should record body sizes and the response read state under template and host, independent of the level gate`() {
            // What is tested: the opt-in body meters - sizes per direction tagged by template and host,
            //   plus the response read state; all recorded although the logger is OFF.
            // Success criteria: request 5 bytes, response 6 bytes, one `complete` count under the tags.
            // Why it matters: a metric must not depend on how loud the logger is configured.
            // Given
            val measuring =
                ClientRequestLoggingInterceptor(
                    properties.copy(measureRequestBodySize = true, measureResponseBodySize = true),
                    { ticker.get() },
                    { "generated-42" },
                    registry,
                )
            log.logger.level = Level.OFF
            val request =
                request(method = org.springframework.http.HttpMethod.POST, uri = "https://api.example.com/things/7").apply {
                    attributes[ClientRequestLoggingInterceptor.URI_TEMPLATE_ATTRIBUTE] = "https://api.example.com/things/{id}"
                }

            // When
            measuring.intercept(request, "hello".toByteArray(), answering(body = "world!")).consumeAndClose()

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
            assertThat(counter(ClientLoggingMetrics.RESPONSE_BODY_READ_METER, *tags, "state", BodyReadState.COMPLETE.tagValue)).isEqualTo(1.0)
            assertThat(log.events).isEmpty()
        }

        @Test
        fun `should count an unread response body and record no size sample for it`() {
            // What is tested: recordBodySizes for a response the application closed without opening
            //   the body - the capture stays UNREAD at zero bytes, a response exists, and no template
            //   attribute was recorded.
            // Success criteria: adapter.response.body.read counts 1.0 under uri=UNKNOWN, the peer
            //   host and state=unread; no response body size summary exists because recordBodySize
            //   skips zero bytes.
            // Why it matters: the read-state counter is the one place a discarded payload becomes
            //   visible - the size summary cannot show it, and a zero sample there would distort the
            //   distribution of bodies that exist.
            // Given: measuring, and a response closed without reading
            val measuring =
                ClientRequestLoggingInterceptor(properties.copy(measureResponseBodySize = true), { ticker.get() }, { "generated-42" }, registry)

            // When
            measuring.intercept(request(), ByteArray(0), answering(body = "dropped")).close()

            // Then: unread counted under UNKNOWN template; no summary sample (zero bytes flowed)
            assertThat(counter(ClientLoggingMetrics.RESPONSE_BODY_READ_METER, "uri", "UNKNOWN", "host", "api.example.com", "state", "unread"))
                .isEqualTo(1.0)
            assertThat(registry.find(ClientLoggingMetrics.RESPONSE_BODY_SIZE_METER).summary()).isNull()
        }

        @Test
        fun `should not record a read state when the call produced no response`() {
            // What is tested: the exchange.response != null guard in recordBodySizes - a call that
            //   threw before a status line leaves the measuring-mode capture with nothing to consume.
            // Success criteria: no adapter.response.body.read counter is created at all after the
            //   refused call.
            // Why it matters: counting such a call as unread would blame the application for
            //   discarding a body the peer never sent, inflating exactly the share the counter exists
            //   to flag.
            // Given
            val measuring =
                ClientRequestLoggingInterceptor(properties.copy(measureResponseBodySize = true), { ticker.get() }, { "generated-42" }, registry)

            // When
            catchThrowable { measuring.intercept(request(), ByteArray(0)) { _, _ -> throw IOException("refused") } }

            // Then: nothing to consume, nothing counted
            assertThat(registry.find(ClientLoggingMetrics.RESPONSE_BODY_READ_METER).counter()).isNull()
        }
    }

    @Nested
    inner class `Fail-open stages` {
        @Test
        fun `should degrade to a pass-through and count stage wiring when the id generator throws`() {
            // What is tested: the wiring guard - a host-provided bean throwing at wiring time.
            // Success criteria: the call succeeds untouched, no event, wiring=1, gauge untouched.
            // Why it matters: a logging component must never fail the call it describes.
            // Given
            val broken = ClientRequestLoggingInterceptor(properties, { ticker.get() }, { throw IllegalStateException("no ids") }, registry)

            // When
            val body = broken.intercept(request(), ByteArray(0), answering(body = "served")).consumeAndClose()

            // Then
            assertThat(body).isEqualTo("served")
            assertThat(log.events).isEmpty()
            assertThat(counter(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "wiring")).isEqualTo(1.0)
            assertThat(gauge()).isZero()
        }

        @Test
        fun `should count a broken emission as stage emission and never disturb the close`() {
            // What is tested: the emission guard covers the whole emission including its pre-gate
            //   section - here the injected time source throws on its emission-time read.
            // Success criteria: close() returns normally, no event, emission=1, gauge back at zero.
            // Why it matters: the emission counter is the metric channel for exactly this loss.
            // Given: a time source that works at wiring and throws at emission
            val reads = AtomicLong(0)
            val flaky =
                ClientRequestLoggingInterceptor(
                    properties,
                    NanoTimeSource { if (reads.incrementAndGet() == 1L) 0L else throw IllegalStateException("clock broke") },
                    CorrelationIdGenerator { "generated-42" },
                    registry,
                )

            // When
            val response = flaky.intercept(request(), ByteArray(0), answering())
            val thrown = catchThrowable { response.close() }

            // Then
            assertThat(thrown).isNull()
            assertThat(log.events).isEmpty()
            assertThat(counter(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "emission")).isEqualTo(1.0)
            assertThat(gauge()).isZero()
        }

        @Test
        fun `should confine an arrival-line backend failure and count stage arrival`() {
            // What is tested: the arrival guard's coverage - the logger-level gate is a backend call and
            //   must sit INSIDE the fail-open guard.
            // Success criteria: with a logging backend whose level check throws (a throwing TurboFilter
            //   - logback consults turbo filters inside isInfoEnabled), the call is served untouched and
            //   the loss is counted as stage arrival; the completion event still follows.
            // Why it matters: the arrival line is OPTIONAL observability; it failing the call would
            //   invert the module's central contract.
            // Given
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
                val announcing = ClientRequestLoggingInterceptor(properties.copy(logRequestStart = true), { ticker.get() }, { "generated-42" }, registry)

                // When: the arrival line fails, then the backend recovers for the completion event
                val response = announcing.intercept(request(), ByteArray(0), answering(body = "served"))
                armed = false
                val body = response.consumeAndClose()

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
            // What is tested: fail-open registration - a host registry already holding a client.* id
            //   with a different meter type.
            // Success criteria: construction succeeds, the call is logged, the conflicting meter is
            //   absent from the host registry (kept private).
            // Why it matters: a registration conflict must never abort the context or suppress events.
            // Given: the events meter's success id taken by a gauge
            val conflicting: MeterRegistry = SimpleMeterRegistry()
            io.micrometer.core.instrument.Gauge
                .builder(ClientLoggingMetrics.EVENTS_METER) { 1.0 }
                .tag("outcome", "success")
                .register(conflicting)

            // When
            val onConflict = ClientRequestLoggingInterceptor(properties, { ticker.get() }, { "generated-42" }, conflicting)
            onConflict.intercept(request(), ByteArray(0), answering()).consumeAndClose()

            // Then: the call is logged, the host's gauge is untouched, the conflicting counter stayed
            //   private, the non-conflicting ones landed in the host registry
            assertThat(log.events).hasSize(1)
            assertThat(conflicting.find(ClientLoggingMetrics.EVENTS_METER).tag("outcome", "success").gauge()).isNotNull()
            assertThat(conflicting.find(ClientLoggingMetrics.EVENTS_METER).tag("outcome", "success").counter()).isNull()
            assertThat(conflicting.find(ClientLoggingMetrics.EVENTS_METER).tag("outcome", "failure").counter()).isNotNull()
            assertThat(conflicting.find(ClientLoggingMetrics.FAIL_OPEN_METER).counters()).hasSize(3)
        }

        @Test
        fun `should count a throwing host counter as stage wiring and still log the exchange`() {
            // What is tested: updateQuietly around the correlation counter's increment at wiring
            //   time - a host Counter that registered fine but throws on increment.
            // Success criteria: the exchange is logged as usual and the fail-open counter shows
            //   exactly one stage=wiring increment on the hostile registry.
            // Why it matters: a bookkeeping failure in a host meter must degrade to a lost count,
            //   never turn the call into an unlogged pass-through.
            // Given: a registry whose correlation counter throws on increment
            val hostile: MeterRegistry =
                object : SimpleMeterRegistry() {
                    override fun newCounter(id: io.micrometer.core.instrument.Meter.Id): Counter {
                        val real = super.newCounter(id)
                        if (id.name != ClientLoggingMetrics.CORRELATION_METER) return real
                        return object : Counter by real {
                            override fun increment(amount: Double) = throw IllegalStateException("counter broke")
                        }
                    }
                }
            val onHostile = ClientRequestLoggingInterceptor(properties, { ticker.get() }, { "generated-42" }, hostile)

            // When
            onHostile.intercept(request(), ByteArray(0), answering()).consumeAndClose()

            // Then: the exchange is logged, the bookkeeping loss is counted
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
        // Success criteria: the gauge is found under client=restclient.
        // Why it matters: Micrometer deduplicates by id and keeps the FIRST gauge registered under a
        //   bare name - in a host carrying both twins the second twin's open exchanges would vanish.
        // Given/When/Then
        assertThat(
            registry
                .get(ClientLoggingMetrics.OPEN_EXCHANGES_METER)
                .tag(ClientLoggingMetrics.CLIENT_TAG, "restclient")
                .gauge()
                .value(),
        ).isZero()
    }
}
