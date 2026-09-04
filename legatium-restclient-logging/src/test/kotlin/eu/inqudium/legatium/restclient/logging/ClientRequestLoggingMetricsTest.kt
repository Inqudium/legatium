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
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.converter.ByteArrayHttpMessageConverter
import org.springframework.mock.http.client.MockClientHttpResponse
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
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

    /**
     * A body stream that honours the InputStream contract for a zero-length read (returns 0), as the
     * engines' streams do (verified against the JDK HttpClient's). Spring's mock body is a
     * ByteArrayInputStream, which returns -1 for a zero-length read at its end - and `readNBytes(n)`
     * ends with exactly such a read, so the mock would show the EOF that a real engine never shows.
     */
    private fun engineLikeStream(body: String): InputStream =
        object : FilterInputStream(ByteArrayInputStream(body.toByteArray())) {
            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int = if (len == 0) 0 else super.read(b, off, len)
        }

    /** An execution answering 200 with [body] on an engine-like stream and the given extra headers. */
    private fun answeringLikeAnEngine(
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): ClientHttpRequestExecution =
        ClientHttpRequestExecution { _, _ ->
            MockClientHttpResponse(engineLikeStream(body), HttpStatus.OK).apply {
                this.headers.contentLength = body.length.toLong()
                headers.forEach { (name, value) -> this.headers.set(name, value) }
            }
        }

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
        fun `should not record a request body size sample when the call produced no response`() {
            // What is tested: the exchange.response != null guard on the REQUEST sample in
            //   recordBodySizes - the interceptor copies the serialized body before the wire call,
            //   and a refused connection means none of it reached the peer.
            // Success criteria: after a refused POST with a 4-byte body no adapter.request.body.size
            //   summary exists; after an answered POST with the same body the summary records 4.
            // Why it matters: the meter is documented as bytes that actually flowed; a sample for a
            //   body the peer never saw would inflate payload distributions with every outage and
            //   make the twin comparison lie (the reactive twin tees at the connector write).
            // Given
            val measuring =
                ClientRequestLoggingInterceptor(properties.copy(measureRequestBodySize = true), { ticker.get() }, { "generated-42" }, registry)

            // When: refused, then answered
            catchThrowable { measuring.intercept(request(method = org.springframework.http.HttpMethod.POST), "four".toByteArray()) { _, _ -> throw IOException("refused") } }
            assertThat(registry.find(ClientLoggingMetrics.REQUEST_BODY_SIZE_METER).summary()).isNull()
            measuring.intercept(request(method = org.springframework.http.HttpMethod.POST), "four".toByteArray(), answering()).consumeAndClose()

            // Then: the answered call is the one that recorded
            assertThat(registry.get(ClientLoggingMetrics.REQUEST_BODY_SIZE_METER).summary().totalAmount()).isEqualTo(4.0)
        }

        @Test
        fun `should count a body the ByteArray converter reads to its declared length as complete`() {
            // What is tested: the declared-length completion rule - Spring's ByteArrayHttpMessageConverter
            //   reads exactly Content-Length bytes with readNBytes and never asks for the EOF; the
            //   capture learns the declared length at handover and completes when the count reaches it.
            // Success criteria: the real converter reads the 6-byte body; adapter.response.body.read
            //   counts 1 under state=complete and nothing under state=partial.
            // Why it matters: before the rule every byte[] answer counted as partial - a dashboard
            //   alarm for abandoned body processing fired on healthy calls.
            // Given: measuring, and a response that declares its length on an engine-like stream (a
            //   zero-length read at the end returns 0, not the -1 Spring's mock body would give)
            val measuring =
                ClientRequestLoggingInterceptor(properties.copy(measureResponseBodySize = true), { ticker.get() }, { "generated-42" }, registry)
            val response = measuring.intercept(request(), ByteArray(0), answeringLikeAnEngine("world!"))

            // When: the real Spring converter reads the body, then the client closes
            val read = ByteArrayHttpMessageConverter().read(ByteArray::class.java, response)
            response.close()

            // Then
            assertThat(read).isEqualTo("world!".toByteArray())
            assertThat(counter(ClientLoggingMetrics.RESPONSE_BODY_READ_METER, "uri", "UNKNOWN", "host", "api.example.com", "state", "complete")).isEqualTo(1.0)
            assertThat(registry.find(ClientLoggingMetrics.RESPONSE_BODY_READ_METER).tag("state", "partial").counter()).isNull()
        }

        @Test
        fun `should keep counting a length-exact read as partial when a Content-Encoding makes the length untrustworthy`() {
            // What is tested: the conservative side of the declared-length rule - with a
            //   Content-Encoding on the response an engine may hand the application a decoded body of
            //   another length, so the capture must not trust Content-Length and falls back to the EOF.
            // Success criteria: the same length-exact read as above counts state=partial.
            // Why it matters: a wrong `complete` is worse than a conservative `partial` - the rule may
            //   only fire where the declared length is the length the application reads.
            // Given: measuring, a response declaring length AND encoding, on an engine-like stream
            val measuring =
                ClientRequestLoggingInterceptor(properties.copy(measureResponseBodySize = true), { ticker.get() }, { "generated-42" }, registry)
            val response = measuring.intercept(request(), ByteArray(0), answeringLikeAnEngine("world!", mapOf("Content-Encoding" to "gzip")))

            // When: exactly the declared length is read, no EOF asked for
            response.body.readNBytes(6)
            response.close()

            // Then
            assertThat(counter(ClientLoggingMetrics.RESPONSE_BODY_READ_METER, "uri", "UNKNOWN", "host", "api.example.com", "state", "partial")).isEqualTo(1.0)
        }

        @Test
        fun `should fold a malformed Content-Length to unknown without counting a wiring failure`() {
            // What is tested: the peer-controlled header at the completeness seam - Spring parses
            //   Content-Length with Long.parseLong, so a non-numeric value throws; declaredBodyLength
            //   must fold it to UNKNOWN_LENGTH itself instead of letting the snapshot's catch count
            //   and warn.
            // Success criteria: the status is on the event, no failopen{stage=wiring} increment, no
            //   warning on the interceptor's logger, and the EOF rule alone decides: a length-exact
            //   read without an EOF counts partial.
            // Why it matters: a peer must not be able to raise a warning and a fail-open count per
            //   answer with one garbage header; the header may only ever feed the comparison.
            // Given: measuring, a garbage Content-Length on an engine-like stream, the module logger captured
            val measuring =
                ClientRequestLoggingInterceptor(properties.copy(measureResponseBodySize = true), { ticker.get() }, { "generated-42" }, registry)
            val garbage =
                ClientHttpRequestExecution { _, _ ->
                    MockClientHttpResponse(engineLikeStream("world!"), HttpStatus.OK).apply { headers.set("Content-Length", "abc") }
                }
            val moduleLog = CapturedLogger(ClientRequestLoggingInterceptor::class.java.name)
            try {
                // When
                val response = measuring.intercept(request(), ByteArray(0), garbage)
                response.body.readNBytes(6)
                response.close()

                // Then
                assertThat(keyValues(log.events.single())).containsEntry("adapter_response_status_code", 200)
                assertThat(counter(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "wiring")).isZero()
                assertThat(moduleLog.events).isEmpty()
                assertThat(counter(ClientLoggingMetrics.RESPONSE_BODY_READ_METER, "uri", "UNKNOWN", "host", "api.example.com", "state", "partial")).isEqualTo(1.0)
            } finally {
                moduleLog.detach()
            }
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
    fun `should keep the gauge private with a warning when the host registry already holds an identical gauge`() {
        // What is tested: the same-type collision check of the gauge registration - a host (or an
        //   older library copy) already registered adapter.logging.exchanges.open{client=restclient};
        //   Micrometer would return that gauge unchanged and drop this owner's state function.
        // Success criteria: the host's gauge keeps its own value (7) while an exchange is open, the
        //   registry holds exactly one meter under the id, one WARN on the metrics logger names the
        //   meter as kept private, and the exchange is still logged.
        // Why it matters: without the check the liveness gauge showed a foreign value and this
        //   instance's open exchanges were invisible - no fallback, no warning, the one silent-loss
        //   signal itself lost silently.
        // Given: a host gauge under the exact id, and the metrics logger captured
        val hostRegistry = SimpleMeterRegistry()
        val hostState = AtomicLong(7)
        io.micrometer.core.instrument.Gauge
            .builder(ClientLoggingMetrics.OPEN_EXCHANGES_METER, hostState) { it.get().toDouble() }
            .tag(ClientLoggingMetrics.CLIENT_TAG, "restclient")
            .register(hostRegistry)
        val metricsLog = CapturedLogger(ClientLoggingMetrics::class.java.name)
        try {
            // When
            val onCollision = ClientRequestLoggingInterceptor(properties, { ticker.get() }, { "generated-42" }, hostRegistry)
            val response = onCollision.intercept(request(), ByteArray(0), answering())
            val hostGaugeWhileOpen =
                hostRegistry
                    .get(ClientLoggingMetrics.OPEN_EXCHANGES_METER)
                    .tag(ClientLoggingMetrics.CLIENT_TAG, "restclient")
                    .gauge()
                    .value()
            response.close()

            // Then
            assertThat(hostGaugeWhileOpen).isEqualTo(7.0)
            assertThat(hostRegistry.find(ClientLoggingMetrics.OPEN_EXCHANGES_METER).meters()).hasSize(1)
            val warning = metricsLog.events.single()
            assertThat(warning.level).isEqualTo(Level.WARN)
            assertThat(warning.formattedMessage).contains(ClientLoggingMetrics.OPEN_EXCHANGES_METER).contains("kept private")
            assertThat(log.events).hasSize(1)
        } finally {
            metricsLog.detach()
        }
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
