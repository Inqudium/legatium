package eu.inqudium.legatium.webclient.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import eu.inqudium.legatium.common.CapturedLogger
import eu.inqudium.legatium.common.ClientLoggingMetrics
import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.ClientStack
import eu.inqudium.legatium.common.CorrelationIdGenerator
import eu.inqudium.legatium.common.NanoTimeSource
import eu.inqudium.legatium.common.count
import eu.inqudium.legatium.common.keyValues
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.http.client.reactive.MockClientHttpRequest
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.ExchangeStrategies
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.io.IOException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * The six meters of [ClientLoggingMetrics] as driven by the filter - the lifecycle facts only this entry
 * point can show: pre-registration with the reactive outcome vocabulary, the events counter per outcome,
 * the open-exchanges gauge across the body's lifetime, the request-id source counter, the body meters
 * and the fail-open stages inside Reactor's signal propagation. The registration behaviour of the shared
 * owner itself (fallback registry, gauge collision, guarded updates, tag folding) is tested once, in
 * `ClientLoggingMetricsTest` of legatium-common.
 */
class ClientRequestLoggingMetricsTest {
    private val ticker = AtomicLong(0)
    private val registry = SimpleMeterRegistry()
    private val properties = ClientLoggingProperties(loggerName = "adapter-http-exchange-reactive-metrics-test")
    private val filter = filterWith(properties, ticker, registry)
    private val log = CapturedLogger(properties.loggerName)

    /** The tag values of this stack's outcome vocabulary, pinned by `TwinContractTest`. */
    private val outcomes = ClientStack.WEBCLIENT.outcomes.map { it.tagValue }

    @AfterEach
    fun tearDown() {
        log.detach()
    }

    private fun gauge(): Double = registry.get(ClientLoggingMetrics.OPEN_EXCHANGES_METER).gauge().value()

    @Nested
    inner class `Counters and gauge` {
        @Test
        fun `should pre-register the reactive outcome vocabulary and count emitted events per outcome`() {
            // What is tested: every fixed-tag meter exists at zero before the first call - including the
            //   cancelled outcome the blocking twin does not have - and the events counter counts by
            //   outcome.
            // Success criteria: five outcomes at zero, the gauge under client=webclient; one call each
            //   moves its side.
            // Why it matters: a rate() alert must see the zero before the first occurrence; the client
            //   tag is what keeps this twin's gauge apart from the blocking twin's in one host.
            // Given/When/Then: before the first call, every outcome and the gauge exist at zero
            outcomes.forEach {
                assertThat(registry.count(ClientLoggingMetrics.EVENTS_METER, "outcome", it)).describedAs(it).isZero()
            }
            assertThat(
                registry
                    .get(ClientLoggingMetrics.OPEN_EXCHANGES_METER)
                    .tag(ClientLoggingMetrics.CLIENT_TAG, "webclient")
                    .gauge()
                    .value(),
            ).isZero()

            // And: one call per outcome moves exactly its side
            filter.call(request(), answering())
            filter.call(request(), answering(status = HttpStatus.NOT_FOUND))
            filter.call(request(), answering(status = HttpStatus.BAD_GATEWAY))
            StepVerifier.create(filter.filter(request(), ExchangeFunction { Mono.error(TimeoutException("t")) })).expectError().verify()
            StepVerifier.create(filter.filter(request(), ExchangeFunction { Mono.never() })).thenCancel().verify()
            outcomes.forEach {
                assertThat(registry.count(ClientLoggingMetrics.EVENTS_METER, "outcome", it)).describedAs(it).isEqualTo(1.0)
            }
        }

        @Test
        fun `should keep the open-exchanges gauge up until the body's terminal signal`() {
            // What is tested: the gauge as the liveness signal of the body-driven emission.
            // Success criteria: 1 after the response was delivered but before the body was consumed,
            //   0 after; a failed call goes up and down within the signal.
            // Why it matters: a body nobody consumes must stay VISIBLE - the gauge baseline is the only
            //   signal for that silent-loss mode.
            // Given: a delivered response, body untouched
            val response = requireNotNull(filter.filter(request(), answering(body = "x")).block())

            // When/Then: up while the body is unread, down at its terminal signal
            assertThat(gauge()).isEqualTo(1.0)
            response.releaseBody().block()
            assertThat(gauge()).isZero()

            // And: a failed call goes up and down within its error signal
            StepVerifier.create(filter.filter(request(), ExchangeFunction { Mono.error(IOException("refused")) })).expectError().verify()
            assertThat(gauge()).isZero()
        }

        @Test
        fun `should count the request-id origin per source`() {
            // What is tested: metrics.requestId with the RequestIdSource ClientIdentity resolved -
            //   a conformant traceparent, an accepted correlation header, and neither.
            // Success criteria: the correlation counter shows one increment under each of trace,
            //   header and generated.
            // Why it matters: a rising `generated` share is the one signal that the host stopped
            //   propagating its trace or correlation context onto outbound calls.
            // Given/When
            filter.call(request { header("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01") }, answering())
            filter.call(request { header("X-Correlation-Id", "c-1") }, answering())
            filter.call(request(), answering())

            // Then
            assertThat(registry.count(ClientLoggingMetrics.CORRELATION_METER, "source", "trace")).isEqualTo(1.0)
            assertThat(registry.count(ClientLoggingMetrics.CORRELATION_METER, "source", "header")).isEqualTo(1.0)
            assertThat(registry.count(ClientLoggingMetrics.CORRELATION_METER, "source", "generated")).isEqualTo(1.0)
        }

        @Test
        fun `should share one metrics owner between two filters on the same registry`() {
            // What is tested: ClientLoggingMetrics.forRegistry handing a second filter on the same
            //   registry the existing owner instead of a new one with an ignored gauge
            //   registration.
            // Success criteria: the gauge read through the FIRST filter's registry moves to 1 and
            //   back to 0 for an exchange the SECOND filter carries.
            // Why it matters: a duplicate owner's gauge would be silently dropped by Micrometer and
            //   that filter's open exchanges would become invisible on the liveness signal.
            // Given
            val second = filterWith(properties, ticker, registry, correlationId = "generated-43")

            // When/Then
            val response = requireNotNull(second.filter(request(), answering()).block())
            assertThat(gauge()).isEqualTo(1.0)
            response.releaseBody().block()
            assertThat(gauge()).isZero()
        }
    }

    @Nested
    inner class `Body meters` {
        @Test
        fun `should record body sizes and the response read state under template and host, independent of the level gate`() {
            // What is tested: recordBodySizes in the emitter with both measure flags on and the
            //   logger at OFF - the request tee through the inserter wrap, the response tee, and
            //   the read-state counter.
            // Success criteria: 5 request bytes and 6 response bytes recorded under the template
            //   and host tags, the read counter at 1 for state=complete, and no event emitted - on
            //   the appender AND on the events counter, which counts after the level gate.
            // Why it matters: metrics run before the level gate; a size sample that vanished when
            //   the logger is quiet would make the meters depend on log configuration.
            // Given
            val measuring = filterWith(properties.copy(measureRequestBodySize = true, measureResponseBodySize = true), ticker, registry)
            log.logger.level = Level.OFF
            val request =
                request(method = HttpMethod.POST, uri = "https://api.example.com/things/7") {
                    attribute(ClientRequestLoggingFilter.URI_TEMPLATE_ATTRIBUTE, "https://api.example.com/things/{id}")
                    attribute(ClientRequestLoggingFilter.ADAPTER_NAME_ATTRIBUTE, "things")
                    body(BodyInserters.fromValue("hello"))
                }
            val writing = WritingExchange { req -> answering(body = "world!").exchange(req) }

            // When
            measuring.call(request, writing)

            // Then: the three body meters share the uri/host/name tag set, the name from the attribute
            val tags = arrayOf("uri", "https://api.example.com/things/{id}", "host", "api.example.com", "name", "things")
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
            assertThat(registry.count(ClientLoggingMetrics.RESPONSE_BODY_READ_METER, *tags, "state", "complete")).isEqualTo(1.0)
            assertThat(log.events).isEmpty()
            // And: the events counter agrees with the appender - it counts after the level gate
            outcomes.forEach { assertThat(registry.count(ClientLoggingMetrics.EVENTS_METER, "outcome", it)).describedAs(it).isZero() }
        }

        @Test
        fun `should record the request sample of a call that failed after sending, without a response`() {
            // What is tested: the stack difference the properties document - the reactive twin tees the
            //   request body at the connector's write, so its size sample needs no response, unlike
            //   the blocking twin's copy-before-the-call, which records only with a response.
            // Success criteria: a connector that writes the body and then fails without a response
            //   leaves 5 request bytes in the summary under the template and host tags, no
            //   response-side meter at all, and one failure event.
            // Why it matters: silently adding the blocking twin's "only with a response" rule here
            //   would make the guide's per-stack table wrong and hide the upload size of exactly the
            //   calls whose upload is the only evidence there is.
            // Given: measuring both directions, a connector that takes the body and then fails
            val measuring = filterWith(properties.copy(measureRequestBodySize = true, measureResponseBodySize = true), ticker, registry)
            val request =
                request(method = HttpMethod.POST, uri = "https://api.example.com/things/7") {
                    attribute(ClientRequestLoggingFilter.URI_TEMPLATE_ATTRIBUTE, "https://api.example.com/things/{id}")
                    body(BodyInserters.fromValue("hello"))
                }
            val failingAfterWrite = WritingExchange { Mono.error(IOException("Connection reset")) }

            // When
            val thrown = catchThrowable { measuring.filter(request, failingAfterWrite).block() }

            // Then: the request sample exists, no response-side meter does
            assertThat(Exceptions.unwrap(thrown)).isInstanceOf(IOException::class.java)
            val tags = arrayOf("uri", "https://api.example.com/things/{id}", "host", "api.example.com")
            assertThat(
                registry
                    .get(ClientLoggingMetrics.REQUEST_BODY_SIZE_METER)
                    .tags(*tags)
                    .summary()
                    .totalAmount(),
            ).isEqualTo(5.0)
            assertThat(registry.find(ClientLoggingMetrics.RESPONSE_BODY_SIZE_METER).summary()).isNull()
            assertThat(registry.find(ClientLoggingMetrics.RESPONSE_BODY_READ_METER).counter()).isNull()
            assertThat(keyValues(log.events.single())).containsEntry("adapter_outcome", "failure")
        }

        @Test
        fun `should count a bodiless 204 as complete`() {
            // What is tested: the read state of an answer that carries no body - the connector hands
            //   over an empty body flux, the tee subscribes and sees it complete at once.
            // Success criteria: adapter.response.body.read counts 1 under state=complete for the 204,
            //   nothing under unread or partial, and no size sample exists (zero bytes).
            // Why it matters: the twin contract of the state tag - the blocking twin counts the same
            //   204 complete at handover although its client never opens the body; a route of deletes
            //   and updates must not read as discarded payload on either stack.
            // Given
            val measuring = filterWith(properties.copy(measureResponseBodySize = true), ticker, registry)

            // When
            measuring.call(request(), ExchangeFunction { Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build()) })

            // Then
            assertThat(registry.count(ClientLoggingMetrics.RESPONSE_BODY_READ_METER, "uri", "UNKNOWN", "host", "api.example.com", "state", "complete")).isEqualTo(1.0)
            assertThat(registry.find(ClientLoggingMetrics.RESPONSE_BODY_READ_METER).tag("state", "unread").counter()).isNull()
            assertThat(registry.find(ClientLoggingMetrics.RESPONSE_BODY_READ_METER).tag("state", "partial").counter()).isNull()
            assertThat(registry.find(ClientLoggingMetrics.RESPONSE_BODY_SIZE_METER).summary()).isNull()
        }

        @Test
        fun `should count a body released through toBodilessEntity as complete with its bytes on the size sample`() {
            // What is tested: the documented reactive observation point for a body the application
            //   discards through Spring's API - toBodilessEntity() calls releaseBody(), which subscribes
            //   and drains the body through the tee.
            // Success criteria: state=complete at 1 and a 7-byte size sample for the released body; no
            //   unread count.
            // Why it matters: this is where the twins differ by construction (the blocking twin never
            //   opens that body and counts unread) and the guide says so; a test pins the reactive side
            //   of that sentence so a change in Spring's release path is noticed.
            // Given
            val measuring = filterWith(properties.copy(measureResponseBodySize = true), ticker, registry)

            // When
            val entity = measuring.filter(request(), answering(body = "dropped")).flatMap { it.toBodilessEntity() }.block()

            // Then
            assertThat(requireNotNull(entity).statusCode.value()).isEqualTo(200)
            assertThat(registry.count(ClientLoggingMetrics.RESPONSE_BODY_READ_METER, "uri", "UNKNOWN", "host", "api.example.com", "state", "complete")).isEqualTo(1.0)
            assertThat(registry.find(ClientLoggingMetrics.RESPONSE_BODY_READ_METER).tag("state", "unread").counter()).isNull()
            assertThat(registry.get(ClientLoggingMetrics.RESPONSE_BODY_SIZE_METER).summary().totalAmount()).isEqualTo(7.0)
        }

        @Test
        fun `should count Spring's body skip for a Void body type as partial`() {
            // What is tested: the other documented reactive observation point - bodyToMono(Void.class)
            //   drains the body through takeWhile(release; false), which cancels upstream in onNext of
            //   the FIRST buffer, so the tee sees a subscription but never the completion signal.
            // Success criteria: state=partial at 1 (never complete or unread); the size sample carries the
            //   first buffer only.
            // Why it matters: BodyReadState's KDoc defines the `state` tag by this idiom; the behaviour
            //   rests on Spring's release internals, which an upgrade can change without anything else
            //   turning red - this pin turns red.
            // Given: a two-buffer body the caller declares it does not want
            val measuring = filterWith(properties.copy(measureResponseBodySize = true), ticker, registry)
            val withBody = ClientResponse.create(HttpStatus.OK).body(Flux.just(buffer("ack"), buffer("nowledged"))).build()

            // When
            measuring.filter(request(), ExchangeFunction { Mono.just(withBody) }).flatMap { it.bodyToMono(Void::class.java) }.block()

            // Then
            assertThat(registry.count(ClientLoggingMetrics.RESPONSE_BODY_READ_METER, "uri", "UNKNOWN", "host", "api.example.com", "state", "partial")).isEqualTo(1.0)
            assertThat(registry.find(ClientLoggingMetrics.RESPONSE_BODY_READ_METER).tag("state", "complete").counter()).isNull()
            assertThat(registry.find(ClientLoggingMetrics.RESPONSE_BODY_READ_METER).tag("state", "unread").counter()).isNull()
            assertThat(registry.get(ClientLoggingMetrics.RESPONSE_BODY_SIZE_METER).summary().totalAmount()).isEqualTo(3.0)
        }

        @Test
        fun `should not record a read state when the call produced no response`() {
            // What is tested: the `exchange.response != null` guard around metrics.responseBodyRead
            //   for a connector error before any status line.
            // Success criteria: no counter exists under adapter.response.body.read after the failed
            //   call.
            // Why it matters: a call that never got an answer has no body to consume; counting it
            //   as `unread` would inflate the discarded-payload share the counter exists to show.
            // Given
            val measuring = filterWith(properties.copy(measureResponseBodySize = true), ticker, registry)

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
            // What is tested: wireOrNull catching an exception from ClientIdentity.resolve and
            //   returning null, so filter() falls through to next.exchange(request).
            // Success criteria: the caller receives the body, no event is logged, the fail-open
            //   counter shows stage=wiring at 1 and the gauge is back at 0.
            // Why it matters: a broken host collaborator must cost the log line, never the call -
            //   and the gauge must not be left up by an exchange that was never opened.
            // Given
            val broken = ClientRequestLoggingFilter(properties, NanoTimeSource { ticker.get() }, CorrelationIdGenerator { error("no ids") }, registry)

            // When
            val body = broken.call(request(), answering(body = "served"))

            // Then
            assertThat(body).isEqualTo("served")
            assertThat(log.events).isEmpty()
            assertThat(registry.count(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "wiring")).isEqualTo(1.0)
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
                    NanoTimeSource { if (reads.incrementAndGet() == 1L) 0L else error("clock broke") },
                    CorrelationIdGenerator { "generated-42" },
                    registry,
                )

            // When
            val body = flaky.call(request(), answering(body = "served"))

            // Then
            assertThat(body).isEqualTo("served")
            assertThat(log.events).isEmpty()
            assertThat(registry.count(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "emission")).isEqualTo(1.0)
            assertThat(gauge()).isZero()
        }

        @Test
        fun `should confine an arrival-line backend failure and count stage arrival`() {
            // What is tested: the failOpen guard of logRequestStart - a TurboFilter throws on the
            //   INFO level check of the exchange logger while the arrival line is being written.
            // Success criteria: the exchange proceeds, the caller gets the body, the fail-open
            //   counter shows stage=arrival at 1, and the completion line (armed off by then) is
            //   still emitted.
            // Why it matters: the start line is optional and must never take the call or the
            //   completion line down with it when the logging backend misbehaves.
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
                        if (armed && logger.name == exchangeLogger && level == Level.INFO) error("backend broke")
                        return FilterReply.NEUTRAL
                    }
                }
            context.addTurboFilter(throwing)
            try {
                val announcing = filterWith(properties.copy(logRequestStart = true), ticker, registry)

                // When
                val response = requireNotNull(announcing.filter(request(), answering(body = "served")).block())
                armed = false
                val body = response.bodyToMono(String::class.java).block()

                // Then
                assertThat(body).isEqualTo("served")
                assertThat(registry.count(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "arrival")).isEqualTo(1.0)
                assertThat(log.events).hasSize(1)
            } finally {
                context.turboFilterList.remove(throwing)
            }
        }

        @Test
        fun `should close the gauge and attempt no event when the arrival line dies with an Error`() {
            // What is tested: the Throwable boundary around the arrival line - a logging backend that
            //   dies with an Error (a LinkageError, the likeliest source) while the start line is being
            //   written, inside the defer, before any operator of this filter exists.
            // Success criteria: the Error reaches the caller, the open-exchanges gauge is back at zero,
            //   no event and no arrival-stage count (the Error is outside the fail-open promise), and
            //   the connector was never called.
            // Why it matters: before the arrival line moved inside the try, such an Error left the
            //   exchange open on the gauge forever - a false "bodies are never consumed" alarm from a
            //   backend failure the module never caused.
            // Given: a backend whose level check dies for the exchange logger at INFO
            val context = LoggerFactory.getILoggerFactory() as LoggerContext
            val exchangeLogger = properties.loggerName
            val dying =
                object : TurboFilter() {
                    override fun decide(
                        marker: Marker?,
                        logger: Logger,
                        level: Level,
                        format: String?,
                        params: Array<Any>?,
                        t: Throwable?,
                    ): FilterReply {
                        if (logger.name == exchangeLogger && level == Level.INFO) throw LinkageError("backend died")
                        return FilterReply.NEUTRAL
                    }
                }
            context.addTurboFilter(dying)
            try {
                val announcing = filterWith(properties.copy(logRequestStart = true), ticker, registry)
                var connectorCalled = false

                // When
                val thrown =
                    catchThrowable {
                        announcing
                            .filter(
                                request(),
                                ExchangeFunction {
                                    connectorCalled = true
                                    answering().exchange(it)
                                },
                            ).block()
                    }

                // Then
                assertThat(Exceptions.unwrap(thrown)).isInstanceOf(LinkageError::class.java)
                assertThat(connectorCalled).isFalse()
                assertThat(gauge()).isZero()
                assertThat(log.events).isEmpty()
                assertThat(registry.count(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "arrival")).isZero()
            } finally {
                context.turboFilterList.remove(dying)
            }
        }

        @Test
        fun `should close the gauge and attempt no event when the downstream throws an Error while assembling`() {
            // What is tested: abandonExchange - a downstream filter or connector that THROWS an Error
            //   (an AssertionError) instead of returning a Mono, inside the defer.
            // Success criteria: the caller sees an error, the gauge is back at zero, no event, and
            //   the fail-open counter shows no emission or wiring count for it (an Error is outside the
            //   fail-open promise; only the bookkeeping is protected).
            // Why it matters: no operator of this filter exists yet when the assembly throws, so no
            //   signal hook could ever close the exchange - the catch is the only owner.
            // Given/When
            val thrown = catchThrowable { filter.filter(request(), ExchangeFunction { throw AssertionError("assembly broke") }).block() }

            // Then
            assertThat(Exceptions.unwrap(thrown)).isInstanceOf(AssertionError::class.java)
            assertThat(gauge()).isZero()
            assertThat(log.events).isEmpty()
            assertThat(registry.count(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "emission")).isZero()
            assertThat(registry.count(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "wiring")).isZero()
        }

        @Test
        fun `should confine a throwing host events counter inside the terminal callback and still complete the exchange`() {
            // What is tested: the completion path inside Reactor's signal propagation against a host
            //   meter that throws - the events counter increment runs in the emitter after the line is
            //   on the logger and is guarded by ClientLoggingMetrics.updateQuietly (the gauge itself is
            //   a private counter that cannot throw and has no guard of its own).
            // Success criteria: with a registry whose events counter throws, the body completes for the
            //   caller, the event is emitted, and the loss is counted as stage=wiring - not as a lost
            //   emission.
            // Why it matters: an escaping exception there would be rethrown into the caller's pipeline,
            //   and a stage=emission count would claim a line that exists was lost.
            // Given: a registry whose events counter throws on increment
            val hostile: MeterRegistry =
                object : SimpleMeterRegistry() {
                    override fun newCounter(id: Meter.Id): Counter {
                        val real = super.newCounter(id)
                        if (id.name != ClientLoggingMetrics.EVENTS_METER) return real
                        return object : Counter by real {
                            override fun increment(amount: Double) = error("events broke")
                        }
                    }
                }
            val onHostile = filterWith(properties, ticker, hostile)

            // When
            val thrown = catchThrowable { onHostile.call(request(), answering(body = "served")) }

            // Then
            assertThat(thrown).isNull()
            assertThat(log.events).hasSize(1)
            assertThat(hostile.count(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "wiring")).isEqualTo(1.0)
        }
    }
}
