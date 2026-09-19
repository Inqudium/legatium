package eu.inqudium.legatium.webclient.logging

import ch.qos.logback.classic.Level
import eu.inqudium.legatium.common.ClientLoggingMetrics
import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.CorrelationIdGenerator
import eu.inqudium.legatium.common.MdcKeys
import eu.inqudium.legatium.common.NanoTimeSource
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.reactivestreams.Subscription
import org.slf4j.MDC
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.util.pattern.PatternParseException
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Core behavior of [ClientRequestLoggingFilter]: the exchange line (IDENTICAL in format to the
 * RestClient twin's), the level/outcome matrix with the reactive `cancelled` disposition beside
 * `timeout`, the emission at the body's terminal signal, identity handling (ADR-0002 on the outbound
 * side) and activation. Deterministic: injected `AtomicLong` time, pinned id generator, hand-built
 * `ClientRequest`/`ClientResponse`, every signal driven synchronously.
 */
class ClientRequestLoggingFilterTest {
    private val ticker = AtomicLong(0)
    private val meterRegistry = SimpleMeterRegistry()
    private val properties =
        ClientLoggingProperties(
            loggerName = "adapter-http-exchange-reactive-core-test",
            slowRequestThreshold = Duration.ofMillis(200),
        )
    private val filter =
        ClientRequestLoggingFilter(
            properties,
            NanoTimeSource { ticker.get() },
            CorrelationIdGenerator { "generated-42" },
            meterRegistry,
        )

    private val log = CapturedLogger(properties.loggerName)

    @AfterEach
    fun tearDown() {
        log.detach()
    }

    @Nested
    inner class `The exchange line` {
        @Test
        fun `should log the identical line format of the RestClient twin when the body completes`() {
            // What is tested: the format contract - message and adapter_* key-values must be
            //   indistinguishable from legatium-restclient-logging's output.
            // Success criteria: the exact message string and the full field family for a successful GET,
            //   42 ms of measured work between send and the body's completion.
            // Why it matters: identical logging is this module's core requirement - dashboards must not
            //   care which client produced an event.
            // Given: a GET answered 200, the body consumed 42 ms later
            val next = ExchangeFunction { _ -> ticker.addAndGet(42_000_000).let { answering(body = "ok").exchange(request()) } }

            // When: the response Mono completes and the body is consumed - verified as SIGNALS
            StepVerifier
                .create(filter.filter(request(), next).flatMap { it.bodyToMono(String::class.java) })
                .expectNext("ok")
                .verifyComplete()

            // Then
            val event = log.events.single()
            assertThat(event.level).isEqualTo(Level.INFO)
            assertThat(event.formattedMessage)
                .isEqualTo("Adapter http exchange GET https://api.example.com/things -> 200 [adapter_request_id=generated-42]")
            assertThat(keyValues(event))
                .containsEntry("adapter_outcome", "success")
                .containsEntry("adapter_request_method", "GET")
                .containsEntry("adapter_url_host", "api.example.com")
                .containsEntry("adapter_url_path", "/things")
                .containsEntry("adapter_response_status_code", 200)
                .containsEntry("adapter_duration_ms", 42L)
                .doesNotContainKey("adapter_slow")
            assertThat(event.mdcPropertyMap)
                .containsEntry(MdcKeys.REQUEST_ID, "generated-42")
                .containsEntry(MdcKeys.REQUEST_METHOD, "GET")
                .containsEntry(MdcKeys.ROUTE, "https://api.example.com/things")
        }

        @Test
        fun `should emit only at the body's terminal signal and exactly once`() {
            // What is tested: the emission point - the response body's completion - and its
            //   exactly-once guard.
            // Success criteria: a delivered response logs nothing until its body is consumed; releasing
            //   the body logs; consuming the body a second time logs nothing more.
            // Why it matters: emitting when the response Mono completes would log a body of zero bytes
            //   and a duration without the read; a double subscription must not double the event.
            // Given: a delivered response, body untouched
            val response = requireNotNull(filter.filter(request(), answering(body = "later")).block())

            // When/Then
            assertThat(log.events).isEmpty()
            response.releaseBody().block()
            assertThat(log.events).hasSize(1)
            response.releaseBody().block()
            assertThat(log.events).hasSize(1)
        }

        @Test
        fun `should log query, port and URI template as their own fields`() {
            // What is tested: the URL coordinates RequestTarget splits out of the request URI - an
            //   explicit port in the host, the raw query, and the URI_TEMPLATE_ATTRIBUTE the client
            //   records.
            // Success criteria: the message target carries the port and no query; host, path, query
            //   and template land in their own adapter_url_* fields.
            // Why it matters: the query is what changes per call and must not pollute the route
            //   coordinate; the template is the low-cardinality key dashboards group on.
            // Given
            val request =
                request(uri = "http://localhost:8081/things/7?page=2") {
                    attribute(ClientRequestLoggingFilter.URI_TEMPLATE_ATTRIBUTE, "http://localhost:8081/things/{id}")
                }

            // When
            filter.call(request, answering())

            // Then
            val event = log.events.single()
            assertThat(event.formattedMessage).startsWith("Adapter http exchange GET http://localhost:8081/things/7 -> 200")
            assertThat(keyValues(event))
                .containsEntry("adapter_url_host", "localhost:8081")
                .containsEntry("adapter_url_path", "/things/7")
                .containsEntry("adapter_url_query", "page=2")
                .containsEntry("adapter_url_template", "http://localhost:8081/things/{id}")
        }

        @Test
        fun `should log the client's name from the request attribute and leave the field off otherwise`() {
            // What is tested: adapter_name, read from the ADAPTER_NAME_ATTRIBUTE the host sets once per
            //   client (ADR-0009) - present with the attribute, absent without it, absent for a blank one.
            // Success criteria: "billing" lands in adapter_name, replaces the target in the message, and
            //   the URL host stays the sidecar's; a request without the attribute and one with a blank
            //   name carry no adapter_name at all and keep the target in the message.
            // Why it matters: behind an egress sidecar every dependency shares one adapter_url_host; the
            //   name is the field that tells the clients apart, and an empty bucket would only look
            //   like a client.
            // Given: two clients calling through one sidecar host, one named, one not, one blank
            val named =
                request(uri = "http://localhost:15001/billing/invoices/7") {
                    attribute(ClientRequestLoggingFilter.ADAPTER_NAME_ATTRIBUTE, "billing")
                }
            val unnamed = request(uri = "http://localhost:15001/geo/lookup")
            val blank =
                request(uri = "http://localhost:15001/geo/lookup") {
                    attribute(ClientRequestLoggingFilter.ADAPTER_NAME_ATTRIBUTE, "  ")
                }

            // When
            filter.call(named, answering())
            filter.call(unnamed, answering())
            filter.call(blank, answering())

            // Then: the name beside the shared host and in place of the target in the message; no field
            //   and the target in the message without a usable name
            assertThat(log.events).hasSize(3)
            assertThat(keyValues(log.events[0]))
                .containsEntry("adapter_name", "billing")
                .containsEntry("adapter_url_host", "localhost:15001")
            assertThat(log.events[0].formattedMessage)
                .isEqualTo("Adapter http exchange GET billing -> 200 [adapter_request_id=generated-42]")
            assertThat(keyValues(log.events[1])).doesNotContainKey("adapter_name")
            assertThat(log.events[1].formattedMessage)
                .isEqualTo("Adapter http exchange GET http://localhost:15001/geo/lookup -> 200 [adapter_request_id=generated-42]")
            assertThat(keyValues(log.events[2])).doesNotContainKey("adapter_name")
            assertThat(log.events[2].formattedMessage)
                .isEqualTo("Adapter http exchange GET http://localhost:15001/geo/lookup -> 200 [adapter_request_id=generated-42]")
        }

        @Test
        fun `should log the raw request target so percent-encoded control characters cannot forge log lines`() {
            // What is tested: the log-injection guard for the raw request target.
            // Success criteria: path and query appear percent-encoded as sent in every sink; no sink
            //   contains a line break.
            // Why it matters: a URL assembled from untrusted input could otherwise forge complete
            //   exchange lines in every plain-text appender.
            // Given/When
            filter.call(request(uri = "https://api.example.com/th%0Aings?x=%0D%0Ay"), answering())

            // Then
            val event = log.events.single()
            assertThat(event.formattedMessage)
                .isEqualTo("Adapter http exchange GET https://api.example.com/th%0Aings -> 200 [adapter_request_id=generated-42]")
            assertThat(keyValues(event)).containsEntry("adapter_url_path", "/th%0Aings").containsEntry("adapter_url_query", "x=%0D%0Ay")
            assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.ROUTE, "https://api.example.com/th%0Aings")
            assertThat(event.formattedMessage + keyValues(event).values.joinToString() + event.mdcPropertyMap.values.joinToString())
                .doesNotContain("\n", "\r")
        }

        @Test
        fun `should preserve the ambient MDC beside the identity on the emitted event`() {
            // What is tested: the emission scope is an additive overlay - the completing thread's MDC
            //   (here: the caller's, since everything runs synchronously) stays visible.
            // Success criteria: the event carries the seeded key beside adapter_request_id; afterwards
            //   the thread has the seeded key and no client key.
            // Why it matters: the client line must join an inbound request's line by MDC alone.
            // Given
            MDC.put("endpoint_request_id", "inbound-7")
            try {
                // When
                filter.call(request(), answering())

                // Then
                assertThat(log.events.single().mdcPropertyMap)
                    .containsEntry("endpoint_request_id", "inbound-7")
                    .containsEntry(MdcKeys.REQUEST_ID, "generated-42")
                assertThat(MDC.get(MdcKeys.REQUEST_ID)).isNull()
                assertThat(MDC.get("endpoint_request_id")).isEqualTo("inbound-7")
            } finally {
                MDC.clear()
            }
        }
    }

    @Nested
    inner class `Identity per ADR-0002` {
        @Test
        fun `should generate a correlation id and SEND it on a traceless request without one`() {
            // What is tested: ClientIdentity.resolve for a traceless request without a correlation
            //   header - sendCorrelationHeader is true, so wireExchange rebuilds the request with
            //   the header set.
            // Success criteria: the connector received the generated id in X-Correlation-Id and the
            //   event's MDC carries the same id.
            // Why it matters: the peer's inbound line and this outbound line join on that id; a
            //   generated id that stayed local would leave the call unjoinable on the other side.
            // Given: a next function recording the request it receives
            val next = RecordingExchange()

            // When
            filter.call(request(), next)

            // Then: the connector got the header, the event the same id
            assertThat(requireNotNull(next.sent).headers().getFirst(properties.correlationIdHeader)).isEqualTo("generated-42")
            assertThat(log.events.single().mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "generated-42")
        }

        @Test
        fun `should adopt a correlation id already on the request and leave the request untouched`() {
            // What is tested: the acceptance path of the correlation contract - a conformant header
            //   value becomes the request id and sendCorrelationHeader is false, so no rebuild
            //   happens.
            // Success criteria: the connector received the SAME ClientRequest instance and the
            //   message carries "caller-id".
            // Why it matters: a caller that propagates its own id must see it unchanged on the
            //   wire, and the rebuild must be skipped when there is nothing to add.
            // Given
            val next = RecordingExchange()
            val original = request { header(properties.correlationIdHeader, "caller-id") }

            // When
            filter.call(original, next)

            // Then: the very same request object went to the connector
            assertThat(next.sent).isSameAs(original)
            assertThat(log.events.single().formattedMessage).contains("[adapter_request_id=caller-id]")
        }

        @Test
        fun `should use the traceparent trace id as the request id and add no correlation header`() {
            // What is tested: the identity decision of ADR-0002 on the outbound side.
            // Success criteria: adapter_request_id equals the trace id; the connector got the caller's
            //   request untouched although it carried a correlation header too.
            // Why it matters: observational neutrality on a traced call.
            // Given
            val next = RecordingExchange()
            val original =
                request {
                    header("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
                    header(properties.correlationIdHeader, "caller-id")
                }

            // When
            filter.call(original, next)

            // Then
            assertThat(next.sent).isSameAs(original)
            val event = log.events.single()
            assertThat(event.mdcPropertyMap)
                .containsEntry(MdcKeys.REQUEST_ID, "0af7651916cd43dd8448eb211c80319c")
                .containsEntry("traceId", "0af7651916cd43dd8448eb211c80319c")
                .containsEntry("spanId", "b7ad6b7169203331")
            assertThat(event.formattedMessage)
                .endsWith("[adapter_request_id=0af7651916cd43dd8448eb211c80319c traceId=0af7651916cd43dd8448eb211c80319c spanId=b7ad6b7169203331]")
        }

        @Test
        fun `should fall back to the correlation contract when the traceparent is not conformant`() {
            // What is tested: Traceparent.parse rejecting an all-zero trace id, so ClientIdentity
            //   treats the call as traceless.
            // Success criteria: the connector received the generated correlation header and the
            //   event has the generated id without a traceId key.
            // Why it matters: an invalid traceparent must not become the request id - the W3C rule
            //   forbids the value and a downstream join on it would be meaningless.
            // Given: an all-zero (forbidden) trace id
            val next = RecordingExchange()
            val original = request { header("traceparent", "00-00000000000000000000000000000000-b7ad6b7169203331-01") }

            // When
            filter.call(original, next)

            // Then
            assertThat(requireNotNull(next.sent).headers().getFirst(properties.correlationIdHeader)).isEqualTo("generated-42")
            assertThat(log.events.single().mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "generated-42").doesNotContainKey("traceId")
        }
    }

    @Nested
    inner class `Levels and outcomes` {
        @Test
        fun `should escalate to WARN with outcome failure for a 5xx answer`() {
            // What is tested: the classify branch for a status >= 500 without an error signal.
            // Success criteria: one WARN event with outcome failure and status 503.
            // Why it matters: the peer answered, so the chain completes normally; without this
            //   branch a broken upstream would log at INFO as a success.
            // Given/When
            filter.call(request(), answering(status = HttpStatus.SERVICE_UNAVAILABLE))

            // Then
            val event = log.events.single()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(keyValues(event)).containsEntry("adapter_outcome", "failure").containsEntry("adapter_response_status_code", 503)
        }

        @Test
        fun `should log ERROR with outcome failure and no status when the exchange errors before a response`() {
            // What is tested: the no-response path - the connector errored before a status line.
            // Success criteria: the error signal propagates unchanged; one ERROR event with the cause,
            //   `-> -` and no status field.
            // Why it matters: a call that never got an answer must still be one truthful line.
            // Given
            val boom = IOException("Connection refused")

            // When
            StepVerifier
                .create(filter.filter(request(), ExchangeFunction { Mono.error(boom) }))
                .expectErrorSatisfies { assertThat(it).isSameAs(boom) }
                .verify()

            // Then
            val event = log.events.single()
            assertThat(event.level).isEqualTo(Level.ERROR)
            assertThat(event.formattedMessage).contains("-> - [")
            assertThat(event.throwableProxy?.message).isEqualTo("Connection refused")
            assertThat(keyValues(event)).containsEntry("adapter_outcome", "failure").doesNotContainKey("adapter_response_status_code")
        }

        @Test
        fun `should log WARN with outcome timeout when the connector raises a timeout`() {
            // What is tested: Timeouts.isTimeout walking the cause chain of the error signal - the
            //   TimeoutException is wrapped one level down, as connectors wrap.
            // Success criteria: one WARN event with outcome timeout and no status field.
            // Why it matters: an operator reads a timeout as "peer slow or unreachable", a failure
            //   as "peer broken"; the distinction must survive the connector's wrapping.
            // Given: a timeout the way a connector raises it - an error signal with a timeout cause
            val timeout = IllegalStateException("response timed out", TimeoutException("Response timed out after 200 ms"))

            // When
            StepVerifier.create(filter.filter(request(), ExchangeFunction { Mono.error(timeout) })).expectError().verify()

            // Then
            val event = log.events.single()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(keyValues(event)).containsEntry("adapter_outcome", "timeout").doesNotContainKey("adapter_response_status_code")
        }

        @Test
        fun `should log outcome cancelled with a dash status when the caller cancels before the response`() {
            // What is tested: the reactive disposition the blocking twin cannot have - a cancelled
            //   subscription before any response (a downstream timeout operator, a disposed caller).
            // Success criteria: one WARN event, outcome cancelled, `-> -`, no status field.
            // Why it matters: a torn-down call must neither log as a success nor invent a status.
            // Given/When: the subscriber cancels while the connector never answers
            StepVerifier
                .create(filter.filter(request(), ExchangeFunction { Mono.never() }))
                .thenCancel()
                .verify()

            // Then
            val event = log.events.single()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(event.formattedMessage).contains("-> - [")
            assertThat(keyValues(event)).containsEntry("adapter_outcome", "cancelled").doesNotContainKey("adapter_response_status_code")
        }

        @Test
        fun `should log a body the consumer stopped reading from within its delivery as success, partially read`() {
            // What is tested: a `take(1)` cancels the body from WITHIN onNext of the first buffer - the
            //   consumer decided it has read enough. That is consumption, not abandonment.
            // Success criteria: INFO, outcome success, the received 200, and the read-state counter shows
            //   `partial` for the exchange.
            // Why it matters: the same signal shape is Spring's own body skip (next test); logging it as
            //   cancelled flags healthy calls at WARN.
            // Given: a measuring filter and a response whose body never ends
            val registry = SimpleMeterRegistry()
            val measuring = filterWith(properties.copy(measureResponseBodySize = true), ticker, registry)
            val endless = ClientResponse.create(HttpStatus.OK).body(Flux.concat(Mono.just(buffer("partial")), Flux.never())).build()
            val response = requireNotNull(measuring.filter(request(), ExchangeFunction { Mono.just(endless) }).block())

            // When: the caller takes one chunk and cancels the rest
            StepVerifier
                .create(response.bodyToFlux(DataBuffer::class.java).take(1))
                .expectNextCount(1)
                .verifyComplete()

            // Then
            val event = log.events.single()
            assertThat(event.level).isEqualTo(Level.INFO)
            assertThat(keyValues(event)).containsEntry("adapter_outcome", "success").containsEntry("adapter_response_status_code", 200)
            assertThat(
                registry
                    .get(ClientLoggingMetrics.RESPONSE_BODY_READ_METER)
                    .tag("state", "partial")
                    .counter()
                    .count(),
            ).isEqualTo(1.0)
        }

        @Test
        fun `should log Spring's body skip for a Void body type as success`() {
            // What is tested: `bodyToMono(Void.class)` (and `toEntity(Void.class)`, an unsupported media
            //   type) drains a body-carrying ClientHttpResponse through takeWhile(release; false), which
            //   cancels upstream in onNext of the FIRST buffer - a framework-internal cancel.
            // Success criteria: INFO, outcome success, the received 200, one event.
            // Why it matters: the fire-and-forget idiom of every WebClient user was logged as cancelled at
            //   WARN, and in on-failure body mode both bodies of the healthy call were written.
            // Given: a response with a body the caller declares it does not want
            val withBody = ClientResponse.create(HttpStatus.OK).body(Flux.just(buffer("ack"), buffer("nowledged"))).build()
            val response = requireNotNull(filter.filter(request(), ExchangeFunction { Mono.just(withBody) }).block())

            // When
            response.bodyToMono(Void::class.java).block()

            // Then
            val event = log.events.single()
            assertThat(event.level).isEqualTo(Level.INFO)
            assertThat(keyValues(event)).containsEntry("adapter_outcome", "success").containsEntry("adapter_response_status_code", 200)
        }

        @Test
        fun `should log outcome cancelled with the received status when the body is cancelled out of band`() {
            // What is tested: a cancel from OUTSIDE a delivery - a timeout operator's timer, a disposed
            //   caller, a client that disconnected - is the caller walking away.
            // Success criteria: one WARN event, outcome cancelled, with the received 200.
            // Why it matters: this is the disposition the reactive stack adds; it must survive the
            //   consumption-limited distinction above.
            // Given: a response whose body never ends, one buffer delivered, then the caller cancels later
            val endless = ClientResponse.create(HttpStatus.OK).body(Flux.concat(Mono.just(buffer("partial")), Flux.never())).build()
            val response = requireNotNull(filter.filter(request(), ExchangeFunction { Mono.just(endless) }).block())
            val subscriber =
                object : BaseSubscriber<DataBuffer>() {
                    override fun hookOnSubscribe(subscription: Subscription) = request(1)
                }
            response.bodyToFlux(DataBuffer::class.java).subscribe(subscriber)

            // When: the delivery is over, the caller disposes the subscription
            subscriber.cancel()

            // Then
            val event = log.events.single()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(keyValues(event)).containsEntry("adapter_outcome", "cancelled").containsEntry("adapter_response_status_code", 200)
        }

        @Test
        fun `should classify a body error with the status already received`() {
            // What is tested: the read-side failure - the status arrived, the body then errored.
            // Success criteria: ERROR, outcome failure, WITH the 200 that was received, cause attached.
            // Why it matters: "200 but failed" is exactly what happened; hiding either half misleads.
            // Given
            val broken = ClientResponse.create(HttpStatus.OK).body(Flux.concat(Mono.just(buffer("half")), Flux.error(IOException("reset")))).build()

            // When
            val thrown = catchThrowable { filter.call(request(), ExchangeFunction { Mono.just(broken) }) }

            // Then
            assertThat(thrown).hasMessageContaining("reset")
            val event = log.events.single()
            assertThat(event.level).isEqualTo(Level.ERROR)
            assertThat(keyValues(event)).containsEntry("adapter_outcome", "failure").containsEntry("adapter_response_status_code", 200)
        }

        @Test
        fun `should escalate to WARN and flag a slow but successful call`() {
            // What is tested: the slow escalation in emitExchange - elapsed nanos reaching the 200
            //   ms threshold lifts INFO to WARN without touching the outcome.
            // Success criteria: one WARN event with adapter_slow=true and outcome success.
            // Why it matters: level carries severity and outcome carries semantics; a slow call
            //   must alert without being counted as a failure.
            // Given
            val next = ExchangeFunction { req -> ticker.addAndGet(200_000_000).let { answering().exchange(req) } }

            // When
            filter.call(request(), next)

            // Then
            val event = log.events.single()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(keyValues(event)).containsEntry("adapter_slow", true).containsEntry("adapter_outcome", "success")
        }

        @Test
        fun `should compare the slow threshold at full precision instead of truncated milliseconds`() {
            // What is tested: the Duration comparison of elapsed time against slowRequestThreshold
            //   at nanosecond precision - a 1.5 ms threshold against 1.0 ms and 1.5 ms of elapsed
            //   time.
            // Success criteria: 1.0 ms is not flagged, 1.5 ms is.
            // Why it matters: a toMillis truncation would turn the 1.5 ms threshold into 1 ms and
            //   flag calls the operator explicitly configured as fast enough.
            // Given
            val precise = filterWith(properties.copy(slowRequestThreshold = Duration.ofNanos(1_500_000)), ticker)

            fun slowFlagAfter(elapsedNanos: Long): Boolean {
                log.appender.list.clear()
                precise.call(request(), ExchangeFunction { req -> ticker.addAndGet(elapsedNanos).let { answering().exchange(req) } })
                return keyValues(log.events.single()).containsKey("adapter_slow")
            }

            // When/Then
            assertThat(slowFlagAfter(1_000_000)).isFalse()
            assertThat(slowFlagAfter(1_500_000)).isTrue()
        }

        @Test
        fun `should turn a downstream filter that throws while assembling into the exchange's error signal`() {
            // What is tested: the Mono.defer around the exchange call - a filter that THROWS instead of
            //   returning Mono.error.
            // Success criteria: the caller sees the error as a signal, one ERROR event, gauge back at 0.
            // Why it matters: invoked bare, the throw would skip the callbacks and leak the gauge.
            // Given/When
            StepVerifier
                .create(filter.filter(request(), ExchangeFunction { throw IllegalStateException("assembly") }))
                .expectErrorMessage("assembly")
                .verify()

            // Then
            assertThat(keyValues(log.events.single())).containsEntry("adapter_outcome", "failure")
            assertThat(meterRegistry.get(ClientLoggingMetrics.OPEN_EXCHANGES_METER).gauge().value()).isZero()
        }
    }

    @Nested
    inner class `Activation and start line` {
        @Test
        fun `should not log a call to an excluded host at all`() {
            // What is tested: ClientActivation.shouldNotFilter on the case-insensitive host
            //   exclusion - the filter returns next.exchange(request) before any wiring.
            // Success criteria: the connector received the very same request instance and no event
            //   exists.
            // Why it matters: a metrics push or health probe target must cost nothing - no rebuild,
            //   no correlation header, no line.
            // Given
            val excluding = filterWith(properties.copy(excludeHosts = listOf("PushGateway.monitoring.svc")), ticker)
            val next = RecordingExchange()
            val original = request(uri = "http://pushgateway.monitoring.svc:9091/metrics/job/x")

            // When
            excluding.call(original, next)

            // Then: passed untouched, nothing logged
            assertThat(next.sent).isSameAs(original)
            assertThat(log.events).isEmpty()
        }

        @Test
        fun `should be active only for paths matching an include pattern and let an exclude win`() {
            // What is tested: include patterns and exclude prefixes together - a matching path, a
            //   non-matching path, and a path that matches the include but starts with the exclude.
            // Success criteria: exactly one event, for /api/things.
            // Why it matters: the include narrows logging to the calls that matter and the exclude
            //   must win over it, or a noisy internal endpoint could not be silenced inside an
            //   included tree.
            // Given
            val scoped = filterWith(properties.copy(includePathPatterns = listOf("/api/**"), excludePathPrefixes = listOf("/api/internal")), ticker)

            // When
            scoped.call(request(uri = "https://h/api/things"), answering())
            scoped.call(request(uri = "https://h/static/logo.png"), answering())
            scoped.call(request(uri = "https://h/api/internal/jobs"), answering())

            // Then
            assertThat(log.events).hasSize(1)
            assertThat(keyValues(log.events.single())).containsEntry("adapter_url_path", "/api/things")
        }

        @Test
        fun `should match activation on the decoded path segments so an encoded variant cannot slip past an exclude`() {
            // What is tested: the PathContainer-based matching - includes match decoded segments,
            //   an encoded slash stays one segment, and the exclude compares against the decoded
            //   path.
            // Success criteria: /%61pi/things is logged with its raw path, /api%2Fthings is not,
            //   and /%61ctuator/health is excluded.
            // Why it matters: a caller that percent-encodes a letter must neither escape an exclude
            //   nor be denied an include; the logged path stays the raw one that went over the
            //   wire.
            // Given
            val scoped = filterWith(properties.copy(includePathPatterns = listOf("/api/**"), excludePathPrefixes = listOf("/actuator/health")), ticker)

            // When
            scoped.call(request(uri = "https://h/%61pi/things"), answering())
            scoped.call(request(uri = "https://h/api%2Fthings"), answering())
            scoped.call(request(uri = "https://h/%61ctuator/health"), answering())

            // Then
            assertThat(log.events).hasSize(1)
            assertThat(keyValues(log.events.single())).containsEntry("adapter_url_path", "/%61pi/things")
        }

        @Test
        fun `should reject an invalid include pattern at construction time`() {
            // What is tested: ClientActivation parsing includePathPatterns once in its constructor.
            // Success criteria: constructing the filter throws PatternParseException naming the
            //   pattern.
            // Why it matters: a bad pattern is a configuration error that must fail the context
            //   start, not throw per call inside the fail-open path and silently degrade every
            //   exchange.
            // Given/When
            val thrown = catchThrowable { filterWith(properties.copy(includePathPatterns = listOf("/api/{unclosed")), ticker) }

            // Then
            assertThat(thrown).isInstanceOfSatisfying(PatternParseException::class.java) { assertThat(it.toDetailedString()).contains("/api/{unclosed") }
        }

        @Test
        fun `should announce the call before the exchange when enabled`() {
            // What is tested: logRequestStart - emitter.logRequestStart runs inside the defer
            //   BEFORE next.exchange, with the exchange identity in its MDC scope.
            // Success criteria: the connector already sees the started line; two events in total,
            //   the first without an outcome but with the request id in the MDC, the last with
            //   outcome success.
            // Why it matters: the arrival line is what shows a call that never returns; it must
            //   carry the same id as the completion line to be joined with it.
            // Given
            val startLogging = filterWith(properties.copy(logRequestStart = true), ticker)
            var eventsAtCallTime = listOf<String>()
            val next =
                ExchangeFunction { req ->
                    eventsAtCallTime = log.events.map { it.formattedMessage }
                    answering().exchange(req)
                }

            // When
            startLogging.call(request(method = HttpMethod.POST), next)

            // Then
            assertThat(eventsAtCallTime)
                .containsExactly("Adapter http exchange started POST https://api.example.com/things [adapter_request_id=generated-42]")
            assertThat(log.events).hasSize(2)
            assertThat(keyValues(log.events.first())).doesNotContainKey("adapter_outcome")
            assertThat(log.events.first().mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "generated-42")
            assertThat(keyValues(log.events.last())).containsEntry("adapter_outcome", "success")
        }
    }

    @Nested
    inner class `The caller's context` {
        private val key = "endpoint_request_id"
        private val accessor = MdcAccessorGuard(key)

        // The body completes on another thread here, and the emission runs there AFTER the terminal
        // signal reached the blocking caller: the events are awaited, not read, and the appender pins
        // each event's MDC to the emitting thread (see AwaitingAppender).
        private val awaiting = AwaitingAppender().apply { start() }

        @BeforeEach
        fun attachAwaiting() {
            MDC.remove(key)
            log.logger.addAppender(awaiting)
        }

        @AfterEach
        fun removeAccessor() {
            log.logger.detachAppender(awaiting)
            awaiting.stop()
            MDC.remove(key)
            accessor.close()
        }

        /** A peer whose body arrives on a bounded-elastic thread - the exchange completes off the caller's thread. */
        private fun answeringElsewhere(): ExchangeFunction =
            ExchangeFunction {
                Mono.just(
                    ClientResponse
                        .create(HttpStatus.OK)
                        .body(Flux.defer { Flux.just(buffer("payload")) }.subscribeOn(Schedulers.boundedElastic()))
                        .build(),
                )
            }

        @Test
        fun `should restore the caller's Reactor Context around the exchange line on the completing thread`() {
            // What is tested: the join of ADR-0010 - a key the caller put into the Reactor Context (the
            //   way limesium puts endpoint_request_id there), an accessor registered for it, and a body
            //   that completes on another thread.
            // Success criteria: the event carries the key from the context in its MDC although it was
            //   logged on a bounded-elastic thread, and the caller's thread carries no such key afterwards.
            // Why it matters: the completing thread never ran the inbound request; without the
            //   restoration the client line and the server line cannot be joined on the reactive stack.
            // Given: no such key on the caller's thread (the fixture removed it), the key in the subscriber's context
            // When
            filter
                .filter(request(), answeringElsewhere())
                .flatMap { it.bodyToMono(String::class.java) }
                .contextWrite { it.put(key, "inbound-7") }
                .block()

            // Then: logged elsewhere, joined anyway; the caller's thread untouched
            val event = awaiting.awaitEvents(1).single()
            assertThat(event.threadName).isNotEqualTo(Thread.currentThread().name)
            assertThat(event.mdcPropertyMap)
                .containsEntry(key, "inbound-7")
                .containsEntry(MdcKeys.REQUEST_ID, "generated-42")
            assertThat(MDC.get(key)).isNull()
        }

        @Test
        fun `should give the arrival line and the completion line the same ambient context`() {
            // What is tested: logRequestStart restores the context exactly like the completion event.
            // Success criteria: both events of one call carry the context's key.
            // Why it matters: the two lines of one exchange are joined by the request id; an ambient
            //   key on one and not the other would make the pair read like two different callers.
            // Given
            val startLogging = filterWith(properties.copy(logRequestStart = true), ticker)

            // When
            startLogging
                .filter(request(), answeringElsewhere())
                .flatMap { it.bodyToMono(String::class.java) }
                .contextWrite { it.put(key, "inbound-7") }
                .block()

            // Then
            val events = awaiting.awaitEvents(2)
            assertThat(events).hasSize(2)
            assertThat(events).allSatisfy { event -> assertThat(event.mdcPropertyMap).containsEntry(key, "inbound-7") }
        }

        @Test
        fun `should carry the same ambient context on every retry attempt`() {
            // What is tested: the context is the subscriber's, so a resubscription by an outer retry
            //   sees the same one - unlike a thread-local snapshot, which the retry scheduler would not
            //   carry.
            // Success criteria: a first attempt that fails at the connector and a second that succeeds
            //   both log with the context's key.
            // Why it matters: two lines of one logical call must join the same server line.
            // Given: a connector failing once, then answering elsewhere
            var attempts = 0
            val flaky =
                ExchangeFunction { req ->
                    if (attempts++ == 0) Mono.error(IOException("connection reset")) else answeringElsewhere().exchange(req)
                }

            // When
            filter
                .filter(request(), flaky)
                .retry(1)
                .flatMap { it.bodyToMono(String::class.java) }
                .contextWrite { it.put(key, "inbound-7") }
                .block()

            // Then
            val events = awaiting.awaitEvents(2)
            assertThat(events).hasSize(2)
            assertThat(keyValues(events[0])).containsEntry("adapter_outcome", "failure")
            assertThat(keyValues(events[1])).containsEntry("adapter_outcome", "success")
            assertThat(events).allSatisfy { event -> assertThat(event.mdcPropertyMap).containsEntry(key, "inbound-7") }
        }

        @Test
        fun `should keep the emitting thread's own value when the context does not mention the key`() {
            // What is tested: the additive contract on a call that completes synchronously on the
            //   caller's thread, whose MDC carries the key while the context does not.
            // Success criteria: the event shows the thread's value; the thread still has it afterwards.
            // Why it matters: a WebClient used blockingly from a servlet thread has the inbound identity
            //   on the thread, not in the context - the restorer must not erase it.
            // Given
            MDC.put(key, "on-thread")

            // When
            filter.call(request(), answering())

            // Then
            assertThat(awaiting.awaitEvents(1).single().mdcPropertyMap).containsEntry(key, "on-thread")
            assertThat(MDC.get(key)).isEqualTo("on-thread")
        }

        @Test
        fun `should log the module's own identity alone when the restorer throws`() {
            // What is tested: restoreAmbientQuietly - the fail-open path around a host accessor that
            //   throws on the emitting thread.
            // Success criteria: the event is still logged with its adapter_* identity and outcome, the
            //   context's key is absent, and the fail-open meter counts one stage=wiring occurrence.
            // Why it matters: the restoration is an extra; a failing extra must cost the ambient keys,
            //   never the event.
            // Given
            filter.emitter.ambientRestorer = AmbientContextRestorer { throw IllegalStateException("accessor refused") }

            // When
            try {
                filter
                    .filter(request(), answering())
                    .flatMap { it.bodyToMono(String::class.java) }
                    .contextWrite { it.put(key, "inbound-7") }
                    .block()
            } finally {
                filter.emitter.ambientRestorer = AmbientContextRestorer.detect()
            }

            // Then
            val event = awaiting.awaitEvents(1).single()
            assertThat(keyValues(event)).containsEntry("adapter_outcome", "success")
            assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "generated-42").doesNotContainKey(key)
            assertThat(
                meterRegistry
                    .get(ClientLoggingMetrics.FAIL_OPEN_METER)
                    .tag("stage", "wiring")
                    .counter()
                    .count(),
            ).isEqualTo(1.0)
        }
    }

    @Nested
    inner class `Subscription and completion shapes` {
        @Test
        fun `should log one line per subscription when an outer filter resubscribes the call`() {
            // What is tested: wiring runs per SUBSCRIPTION - a retrying outer filter that resubscribes
            //   this filter's Mono (retry, retryWhen) must see one exchange per attempt.
            // Success criteria: two subscriptions, two success lines, the gauge back at zero.
            // Why it matters: wired at assembly time, attempt 2 hit a completed exchange and was never
            //   logged - the class documentation promised one line per attempt.
            // Given: the Mono an outer filter would hold
            val call = filter.filter(request(), ExchangeFunction { answering().exchange(it) })

            // When: subscribed twice
            call.flatMap { it.bodyToMono(String::class.java) }.block()
            call.flatMap { it.bodyToMono(String::class.java) }.block()

            // Then
            assertThat(log.events).hasSize(2)
            assertThat(log.events.map { keyValues(it)["adapter_outcome"] }).containsExactly("success", "success")
            assertThat(meterRegistry.get(ClientLoggingMetrics.OPEN_EXCHANGES_METER).gauge().value()).isZero()
        }

        @Test
        fun `should generate and send a fresh correlation id on every attempt of a resubscribed traceless call`() {
            // What is tested: the identity of a traceless call across resubscriptions - resolved per
            //   subscription from the caller's immutable ClientRequest, which never carries the header
            //   this filter adds to its rebuilt copy.
            // Success criteria: two subscriptions, two DIFFERENT generated ids on the lines, each one
            //   sent on the wire of its own attempt, both counted `generated`.
            // Why it matters: this is a documented stack difference (the filter's class KDoc, the README
            //   table): the blocking twin's mutable request keeps the header of attempt 1 and re-sends
            //   the same id. A change to either side must be a visible decision.
            // Given: a counting generator and a connector that records what it is handed
            var next = 0
            val counting = ClientRequestLoggingFilter(properties, NanoTimeSource { ticker.get() }, CorrelationIdGenerator { "gen-${next++}" }, meterRegistry)
            val sentHeaders = mutableListOf<String?>()
            val call =
                counting.filter(
                    request(),
                    ExchangeFunction { sent ->
                        sentHeaders += sent.headers().getFirst(properties.correlationIdHeader)
                        answering().exchange(sent)
                    },
                )

            // When: subscribed twice, as an outer retry does
            call.flatMap { it.bodyToMono(String::class.java) }.block()
            call.flatMap { it.bodyToMono(String::class.java) }.block()

            // Then
            assertThat(log.events.map { it.mdcPropertyMap[MdcKeys.REQUEST_ID] }).containsExactly("gen-0", "gen-1")
            assertThat(sentHeaders).containsExactly("gen-0", "gen-1")
            assertThat(
                meterRegistry
                    .get(ClientLoggingMetrics.CORRELATION_METER)
                    .tag("source", "generated")
                    .counter()
                    .count(),
            ).isEqualTo(2.0)
        }

        @Test
        fun `should log an empty completion of the connector as ERROR failure without a status`() {
            // What is tested: a connector (or a host filter swallowing an error into Mono.empty()) that
            //   completes without a response.
            // Success criteria: ERROR, outcome failure, `-> -`, the cause naming the missing response - the
            //   same words WebClient raises for the caller.
            // Why it matters: the caller sees an error; a success line for it would be a lie.
            // Given/When
            val response = filter.filter(request(), ExchangeFunction { Mono.empty() }).block()

            // Then
            assertThat(response).isNull()
            val event = log.events.single()
            assertThat(event.level).isEqualTo(Level.ERROR)
            assertThat(event.formattedMessage).contains("-> - [")
            assertThat(keyValues(event)).containsEntry("adapter_outcome", "failure").doesNotContainKey("adapter_response_status_code")
            assertThat(event.throwableProxy.message).isEqualTo(ClientRequestLoggingFilter.NO_RESPONSE_MESSAGE)
        }

        @Test
        fun `should let the body own the exchange when a host operator cancels the response Mono after delivery`() {
            // What is tested: an operator between this filter and the client that cancels the response
            //   Mono right after onNext (`next()`, a future bridge) - the response was delivered, the body
            //   is consumed afterwards.
            // Success criteria: one success line at the body's completion, not a cancelled line at the
            //   operator's cancel.
            // Why it matters: the outer cancel arrives while the exchange is RESPONDED; ending it there
            //   would misclassify the call and truncate the logged body.
            // Given/When
            val body =
                filter
                    .filter(request(), ExchangeFunction { answering(body = "payload").exchange(it) })
                    .flux()
                    .next()
                    .flatMap { it.bodyToMono(String::class.java) }
                    .block()

            // Then
            assertThat(body).isEqualTo("payload")
            val event = log.events.single()
            assertThat(keyValues(event)).containsEntry("adapter_outcome", "success").containsEntry("adapter_response_status_code", 200)
        }

        @Test
        fun `should complete the exchange as cancelled when the caller cancels while the response is being delivered`() {
            // What is tested: the handover race - the response is inside the downstream's onNext (state
            //   DELIVERING) when the caller cancels from ANOTHER thread; a downstream that is cancelling
            //   drops the response and never subscribes to the body, so the body can never complete the
            //   exchange. Barrier-driven: the subscriber's onNext waits for the cancel.
            // Success criteria: exactly one WARN event, outcome cancelled with the received 200, and the
            //   open-exchanges gauge back at zero - without the body ever being consumed.
            // Why it matters: with the state set to RESPONDED before the handover, this cancel was
            //   ignored as "the body owns it" and the exchange stayed open forever: no event, the gauge
            //   one too high for the life of the process.
            // Given: a subscriber that holds the delivery until the caller has cancelled; the worker is a
            //   daemon whose uncaught failure is collected, so a hang or a check() that fires inside
            //   Reactor's onNext neither keeps the forked JVM alive nor hides behind the event assertion
            val delivering = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val subscriber =
                object : BaseSubscriber<ClientResponse>() {
                    override fun hookOnNext(value: ClientResponse) {
                        delivering.countDown()
                        check(cancelled.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)) { "the cancel never arrived" }
                    }
                }
            val workerFailure = AtomicReference<Throwable>()
            val worker =
                Thread { filter.filter(request(), answering(body = "payload")).subscribe(subscriber) }.apply {
                    isDaemon = true
                    setUncaughtExceptionHandler { _, t -> workerFailure.set(t) }
                }
            worker.start()
            check(delivering.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)) { "the response was never delivered" }

            // When: the caller cancels from its own thread, mid-delivery, and the delivery then returns
            subscriber.cancel()
            cancelled.countDown()
            worker.join(AWAIT)

            // Then: the worker finished cleanly, and the exchange ended as cancelled
            assertThat(worker.isAlive).describedAs("the delivering worker never returned").isFalse()
            assertThat(workerFailure.get()).describedAs("uncaught failure on the delivering worker").isNull()
            val event = log.events.single()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(keyValues(event)).containsEntry("adapter_outcome", "cancelled").containsEntry("adapter_response_status_code", 200)
            assertThat(meterRegistry.get(ClientLoggingMetrics.OPEN_EXCHANGES_METER).gauge().value()).isZero()
        }

        @Test
        fun `should replace a correlation header outside the acceptance rule with a generated id`() {
            // What is tested: the CorrelationHeader rule at the filter - a forged value counts as absent.
            // Success criteria: the connector receives the generated id INSTEAD of the foreign value; the
            //   event carries the generated id and no trace of the forged one.
            // Why it matters: the value lands verbatim in the message and the MDC.
            // Given
            val next = RecordingExchange()
            val forged = request { header("X-Correlation-Id", "abc\r\nforged=line") }

            // When
            filter.call(forged, next)

            // Then
            val outgoing = requireNotNull(next.sent)
            assertThat(outgoing.headers().getFirst("X-Correlation-Id")).isEqualTo("generated-42")
            assertThat(outgoing.headers()["X-Correlation-Id"]).containsExactly("generated-42")
            val event = log.events.single()
            assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "generated-42")
            assertThat(event.formattedMessage).doesNotContain("forged")
        }
    }
}
