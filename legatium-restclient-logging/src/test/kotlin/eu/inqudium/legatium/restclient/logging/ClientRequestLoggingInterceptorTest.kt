package eu.inqudium.legatium.restclient.logging

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
import org.slf4j.MDC
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.web.util.pattern.PatternParseException
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Core behavior of [ClientRequestLoggingInterceptor]: the exchange line, the level/outcome matrix with
 * the client-side `timeout` disposition, the emission at response close, identity handling (ADR-0002
 * on the outbound side) and activation. Deterministic: injected `AtomicLong` time, pinned id generator,
 * Spring's mock client request/response, no I/O.
 */
class ClientRequestLoggingInterceptorTest {
    private val ticker = AtomicLong(0)
    private val meterRegistry = SimpleMeterRegistry()
    private val properties =
        ClientLoggingProperties(
            loggerName = "http-adapter-exchange-core-test",
            slowRequestThreshold = Duration.ofMillis(200),
        )
    private val interceptor =
        ClientRequestLoggingInterceptor(
            properties,
            NanoTimeSource { ticker.get() },
            CorrelationIdGenerator { "generated-42" },
            meterRegistry,
        )

    private lateinit var log: CapturedLogger

    @BeforeEach
    fun setUp() {
        log = CapturedLogger(properties.loggerName)
    }

    @AfterEach
    fun tearDown() {
        log.detach()
    }

    private fun interceptorWith(properties: ClientLoggingProperties) = ClientRequestLoggingInterceptor(properties, { ticker.get() }, { "generated-42" }, SimpleMeterRegistry())

    @Nested
    inner class `The exchange line` {
        @Test
        fun `should log one line with the client field family at response close`() {
            // What is tested: the format contract - message, adapter_* key-values and MDC of the
            //   completion event, emitted when the client CLOSES the response.
            // Success criteria: the exact message string, the full field family for a successful GET,
            //   the request id in the MDC; 42 ms of measured work between send and close.
            // Why it matters: the line is the module's product; the WebClient twin's test asserts the
            //   identical format, so this pins one half of the twin contract.
            // Given: a GET answered 200 after 42 ms of measured work
            val request = request()
            val execution =
                answering(body = "ok") {
                    ticker.addAndGet(42_000_000)
                }

            // When: the call runs and the client consumes and closes the response
            interceptor.intercept(request, ByteArray(0), execution).consumeAndClose()

            // Then: one INFO line
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
                .doesNotContainKey("adapter_url_template")
                .doesNotContainKey("adapter_url_query")
            assertThat(event.mdcPropertyMap)
                .containsEntry(MdcKeys.REQUEST_ID, "generated-42")
                .containsEntry(MdcKeys.REQUEST_METHOD, "GET")
                .containsEntry(MdcKeys.ROUTE, "https://api.example.com/things")
        }

        @Test
        fun `should emit only when the response is closed and exactly once`() {
            // What is tested: the emission point - response close - and its exactly-once guard.
            // Success criteria: nothing is logged while the response is open (the body may still be
            //   read); the first close logs, a second close logs nothing more.
            // Why it matters: emitting when the interceptor returns would log a body of zero bytes and a
            //   duration without the read; a double close (a client's finally after an explicit close)
            //   must not double the event.
            // Given: a call whose response the client holds open
            val response = interceptor.intercept(request(), ByteArray(0), answering(body = "later"))

            // When/Then: open - nothing yet; closed - one event; closed again - still one
            assertThat(log.events).isEmpty()
            response.body.readAllBytes()
            assertThat(log.events).isEmpty()
            response.close()
            assertThat(log.events).hasSize(1)
            response.close()
            assertThat(log.events).hasSize(1)
        }

        @Test
        fun `should log query, port and URI template as their own fields`() {
            // Given: an explicit port, a query string and the template attribute RestClient records
            val request = request(uri = "http://localhost:8081/things/7?page=2")
            request.attributes[ClientRequestLoggingInterceptor.URI_TEMPLATE_ATTRIBUTE] = "http://localhost:8081/things/{id}"

            // When
            interceptor.intercept(request, ByteArray(0), answering()).consumeAndClose()

            // Then: host with port, path and query split, the template beside the path
            val event = log.events.single()
            assertThat(event.formattedMessage).startsWith("Adapter http exchange GET http://localhost:8081/things/7 -> 200")
            assertThat(keyValues(event))
                .containsEntry("adapter_url_host", "localhost:8081")
                .containsEntry("adapter_url_path", "/things/7")
                .containsEntry("adapter_url_query", "page=2")
                .containsEntry("adapter_url_template", "http://localhost:8081/things/{id}")
        }

        @Test
        fun `should log the raw request target so percent-encoded control characters cannot forge log lines`() {
            // What is tested: the log-injection guard for the raw request target - java.net.URI decodes
            //   getPath()/getQuery(), so `%0A` in the target would become a real line break in the
            //   message, the MDC route and the fields.
            // Success criteria: path and query appear percent-encoded as sent in the message, in the
            //   adapter_url_path/adapter_url_query fields and in the adapter_route MDC entry; no sink
            //   contains a line break.
            // Why it matters: a URL assembled from untrusted input could otherwise forge complete
            //   exchange lines in every plain-text appender.
            // Given: a target with encoded CR/LF in path and query
            val request = request(uri = "https://api.example.com/th%0Aings?x=%0D%0Ay")

            // When
            interceptor.intercept(request, ByteArray(0), answering()).consumeAndClose()

            // Then
            val event = log.events.single()
            assertThat(event.formattedMessage)
                .isEqualTo("Adapter http exchange GET https://api.example.com/th%0Aings -> 200 [adapter_request_id=generated-42]")
            assertThat(keyValues(event))
                .containsEntry("adapter_url_path", "/th%0Aings")
                .containsEntry("adapter_url_query", "x=%0D%0Ay")
            assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.ROUTE, "https://api.example.com/th%0Aings")
            assertThat(event.formattedMessage + keyValues(event).values.joinToString() + event.mdcPropertyMap.values.joinToString())
                .doesNotContain("\n", "\r")
        }

        @Test
        fun `should log a slash path for a URI without a path`() {
            // Given: a bare authority
            interceptor.intercept(request(uri = "https://api.example.com"), ByteArray(0), answering()).consumeAndClose()

            // When/Then: the wire request line asks for "/", and so does the log
            val event = log.events.single()
            assertThat(event.formattedMessage).startsWith("Adapter http exchange GET https://api.example.com/ -> 200")
            assertThat(keyValues(event)).containsEntry("adapter_url_path", "/")
        }

        @Test
        fun `should carry the identity in the MDC while the wire call runs as an additive overlay`() {
            // What is tested: the call scope - inner interceptors and the HTTP engine log under the
            //   client identity, and the ambient MDC (an inbound request's keys, a bridge's keys) stays.
            // Success criteria: inside the execution all three adapter_* keys are set beside a seeded
            //   ambient key; after the call the client keys are gone and the ambient key remains.
            // Why it matters: the client line and every log line of the call must join the server line
            //   by MDC alone, and a pooled thread must not keep the identity.
            // Given: an ambient key on the calling thread
            MDC.put("endpoint_request_id", "inbound-7")
            var seenDuringCall: Map<String, String>? = null
            val execution =
                ClientHttpRequestExecution { _, _ ->
                    seenDuringCall = MDC.getCopyOfContextMap()
                    MockClientHttpResponse(ByteArray(0), HttpStatus.OK)
                }
            try {
                // When
                interceptor.intercept(request(), ByteArray(0), execution).consumeAndClose()

                // Then
                assertThat(seenDuringCall)
                    .containsEntry(MdcKeys.REQUEST_ID, "generated-42")
                    .containsEntry(MdcKeys.REQUEST_METHOD, "GET")
                    .containsEntry(MdcKeys.ROUTE, "https://api.example.com/things")
                    .containsEntry("endpoint_request_id", "inbound-7")
                assertThat(MDC.get(MdcKeys.REQUEST_ID)).isNull()
                assertThat(MDC.get("endpoint_request_id")).isEqualTo("inbound-7")
                // And: the emitted event inherited the ambient key beside its own
                assertThat(log.events.single().mdcPropertyMap).containsEntry("endpoint_request_id", "inbound-7")
            } finally {
                MDC.clear()
            }
        }
    }

    @Nested
    inner class `Identity per ADR-0002` {
        @Test
        fun `should generate a correlation id and SEND it on a traceless request without one`() {
            // What is tested: the outbound mirror of the inbound echo - a traceless call without a
            //   correlation header gets one added so the peer can quote it.
            // Success criteria: the header is on the outgoing request, the event carries the same id.
            // Why it matters: without it the peer's own log line and this line share no identity.
            // Given
            val request = request()

            // When
            interceptor.intercept(request, ByteArray(0), answering()).consumeAndClose()

            // Then
            assertThat(request.headers.getFirst(properties.correlationIdHeader)).isEqualTo("generated-42")
            assertThat(log.events.single().mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "generated-42")
        }

        @Test
        fun `should adopt a correlation id already on the request and leave it untouched`() {
            // Given: the caller put its own id on the request
            val request = request().apply { headers.set(properties.correlationIdHeader, "caller-id") }

            // When
            interceptor.intercept(request, ByteArray(0), answering()).consumeAndClose()

            // Then: adopted, not replaced
            assertThat(request.headers[properties.correlationIdHeader]).containsExactly("caller-id")
            val event = log.events.single()
            assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "caller-id")
            assertThat(event.formattedMessage).contains("[adapter_request_id=caller-id]")
        }

        @Test
        fun `should use the traceparent trace id as the request id and add no correlation header`() {
            // What is tested: the identity decision of ADR-0002 on the outbound side - a conformant
            //   traceparent's trace id IS the request id, a correlation header the caller added is
            //   ignored, and NO correlation header is added.
            // Success criteria: adapter_request_id equals the trace id in MDC and message; the request
            //   carries exactly the caller's headers.
            // Why it matters: a client logger must be observationally neutral - on a traced call the
            //   wire already carries the identity, and adding a second, private id would make enabling
            //   the logger visible to the peer.
            // Given: a traced request that ALSO carries a correlation header
            val request =
                request().apply {
                    headers.set("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
                    headers.set(properties.correlationIdHeader, "caller-id")
                }

            // When
            interceptor.intercept(request, ByteArray(0), answering()).consumeAndClose()

            // Then: the distributed identity outranks the private one, the wire stays untouched
            assertThat(request.headers[properties.correlationIdHeader]).containsExactly("caller-id")
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
            // What is tested: an invalid traceparent counts as ABSENT (ADR-0002) - the traceless
            //   contract applies in full.
            // Success criteria: a fresh id is generated and sent, no trace decoration is emitted.
            // Why it matters: half-trusting a malformed header would mint a request id from bytes the
            //   W3C validation rejected - the strict parser is the single gate for both the trace fields
            //   and the identity decision.
            // Given: an all-zero (forbidden) trace id
            val request = request().apply { headers.set("traceparent", "00-00000000000000000000000000000000-b7ad6b7169203331-01") }

            // When
            interceptor.intercept(request, ByteArray(0), answering()).consumeAndClose()

            // Then
            assertThat(request.headers.getFirst(properties.correlationIdHeader)).isEqualTo("generated-42")
            val event = log.events.single()
            assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "generated-42").doesNotContainKey("traceId")
        }

        @Test
        fun `should own the trace keys at emission so a stale bridge id on the thread cannot ride along`() {
            // What is tested: the emission scope OWNS traceId/spanId - on a traceless call an ambient
            //   traceId (a bridge's, from the server span) is removed for the event and restored after.
            // Success criteria: the event carries no traceId; the thread has it back afterwards.
            // Why it matters: a stale id would join the client event to a trace the call was not part of.
            // Given: an ambient trace id on the thread
            MDC.put("traceId", "ffffffffffffffffffffffffffffffff")
            try {
                // When
                interceptor.intercept(request(), ByteArray(0), answering()).consumeAndClose()

                // Then
                assertThat(log.events.single().mdcPropertyMap).doesNotContainKey("traceId")
                assertThat(MDC.get("traceId")).isEqualTo("ffffffffffffffffffffffffffffffff")
            } finally {
                MDC.clear()
            }
        }
    }

    @Nested
    inner class `Levels and outcomes` {
        @Test
        fun `should escalate to WARN with outcome failure for a 5xx answer`() {
            // Given: the peer answers 503
            interceptor.intercept(request(), ByteArray(0), answering(status = HttpStatus.SERVICE_UNAVAILABLE)).consumeAndClose()

            // When/Then: WARN, outcome failure - severity and semantic decoupled
            val event = log.events.single()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(event.formattedMessage).contains("-> 503 [")
            assertThat(keyValues(event)).containsEntry("adapter_outcome", "failure").containsEntry("adapter_response_status_code", 503)
        }

        @Test
        fun `should log ERROR with outcome failure and no status when the call throws`() {
            // What is tested: the no-response path - the engine threw before a status line arrived.
            // Success criteria: the exception propagates unchanged; one ERROR event with the cause,
            //   `-> -` in the message and no status field; emitted right away, there is nothing to close.
            // Why it matters: a call that never got an answer must still be one line, with the truth
            //   about the missing status rather than an invented one.
            // Given: an execution that fails to connect
            val boom = IOException("Connection refused")

            // When
            val thrown = catchThrowable { interceptor.intercept(request(), ByteArray(0)) { _, _ -> throw boom } }

            // Then
            assertThat(thrown).isSameAs(boom)
            val event = log.events.single()
            assertThat(event.level).isEqualTo(Level.ERROR)
            assertThat(event.formattedMessage).contains("-> - [")
            assertThat(event.throwableProxy?.message).isEqualTo("Connection refused")
            assertThat(keyValues(event)).containsEntry("adapter_outcome", "failure").doesNotContainKey("adapter_response_status_code")
        }

        @Test
        fun `should log WARN with outcome timeout when the call times out`() {
            // What is tested: the client-side disposition worth its own value - a timeout in the cause
            //   chain classifies the failure as `timeout` at WARN.
            // Success criteria: outcome timeout, WARN, cause attached, no status.
            // Why it matters: an operator reads "the peer is slow" differently from "the call is broken".
            // Given: a read timeout wrapped the way engines wrap it
            val timeout = IOException("timed out", SocketTimeoutException("Read timed out"))

            // When
            catchThrowable { interceptor.intercept(request(), ByteArray(0)) { _, _ -> throw timeout } }

            // Then
            val event = log.events.single()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(keyValues(event)).containsEntry("adapter_outcome", "timeout").doesNotContainKey("adapter_response_status_code")
            assertThat(event.throwableProxy?.message).isEqualTo("timed out")
        }

        @Test
        fun `should classify a failure while reading the body with the status already received`() {
            // What is tested: the read-side failure - the status line arrived, the body read then died.
            // Success criteria: the IOException propagates from the read; at close the event is ERROR,
            //   outcome failure, WITH the 200 that was received, cause attached.
            // Why it matters: "200 but failed" is exactly what happened; hiding either half misleads.
            // Given: a response whose body stream breaks mid-read
            val broken =
                object : InputStream() {
                    override fun read(): Int = throw IOException("Connection reset")
                }
            val execution = ClientHttpRequestExecution { _, _ -> MockClientHttpResponse(broken, HttpStatus.OK) }
            val response = interceptor.intercept(request(), ByteArray(0), execution)

            // When
            val thrown = catchThrowable { response.body.read() }
            response.close()

            // Then
            assertThat(thrown).isInstanceOf(IOException::class.java)
            val event = log.events.single()
            assertThat(event.level).isEqualTo(Level.ERROR)
            assertThat(keyValues(event)).containsEntry("adapter_outcome", "failure").containsEntry("adapter_response_status_code", 200)
            assertThat(event.throwableProxy?.message).isEqualTo("Connection reset")
        }

        @Test
        fun `should escalate to WARN and flag a slow but successful call`() {
            // Given: a call that consumes the configured threshold before it is closed
            interceptor
                .intercept(request(), ByteArray(0), answering { ticker.addAndGet(200_000_000) })
                .consumeAndClose()

            // When/Then: WARN + adapter_slow, outcome stays success
            val event = log.events.single()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(keyValues(event)).containsEntry("adapter_slow", true).containsEntry("adapter_outcome", "success")
        }

        @Test
        fun `should measure the duration until close including the body read`() {
            // What is tested: duration = response occupancy, not bare round-trip time.
            // Success criteria: time spent between the interceptor returning and the close is included.
            // Why it matters: a slow body read is the peer's slowness too; the inbound twin measures
            //   request occupancy for the same reason.
            // Given: a fast answer whose body the application reads slowly
            val response = interceptor.intercept(request(), ByteArray(0), answering(body = "x") { ticker.addAndGet(5_000_000) })
            ticker.addAndGet(95_000_000)

            // When
            response.consumeAndClose()

            // Then
            assertThat(keyValues(log.events.single())).containsEntry("adapter_duration_ms", 100L)
        }

        @Test
        fun `should compare the slow threshold at full precision instead of truncated milliseconds`() {
            // What is tested: the threshold comparison - a 1.5 ms threshold must not truncate to 1 ms
            //   and flag a 1 ms call.
            // Success criteria: 1.0 ms is NOT slow, 1.5 ms IS slow, under a 1.5 ms threshold.
            // Why it matters: truncating both sides inflates WARN logs for every threshold with
            //   sub-millisecond precision.
            // Given: a 1.5 ms threshold
            val precise = interceptorWith(properties.copy(slowRequestThreshold = Duration.ofNanos(1_500_000)))

            fun slowFlagAfter(elapsedNanos: Long): Boolean {
                log.appender.list.clear()
                precise.intercept(request(), ByteArray(0), answering { ticker.addAndGet(elapsedNanos) }).consumeAndClose()
                return keyValues(log.events.single()).containsKey("adapter_slow")
            }

            // When/Then
            assertThat(slowFlagAfter(1_000_000)).isFalse()
            assertThat(slowFlagAfter(1_500_000)).isTrue()
        }

        @Test
        fun `should log a WARN breadcrumb on the module logger when the call throws`() {
            // Given: the module's own logger captured
            val internal = CapturedLogger(ClientRequestLoggingInterceptor::class.java.name)
            try {
                // When
                catchThrowable { interceptor.intercept(request(), ByteArray(0)) { _, _ -> throw IOException("boom") } }

                // Then: one WARN breadcrumb naming the call and the id, on the module logger - the
                //   exchange logger keeps its one-event contract
                val breadcrumb = internal.events.single()
                assertThat(breadcrumb.level).isEqualTo(Level.WARN)
                assertThat(breadcrumb.formattedMessage)
                    .isEqualTo("Adapter http exchange failed: GET https://api.example.com/things - java.io.IOException: boom [adapter_request_id=generated-42]")
                assertThat(log.events).hasSize(1)
            } finally {
                internal.detach()
            }
        }
    }

    @Nested
    inner class `Activation and start line` {
        @Test
        fun `should not log a call to an excluded host at all`() {
            // What is tested: the client-side exclusion - by peer host, case-insensitively.
            // Success criteria: the call passes, nothing is logged, no correlation header is added.
            // Why it matters: calls to a metrics gateway or a config server must be silenceable without
            //   knowing their paths.
            // Given
            val excluding = interceptorWith(properties.copy(excludeHosts = listOf("PushGateway.monitoring.svc")))
            val request = request(uri = "http://pushgateway.monitoring.svc:9091/metrics/job/x")

            // When
            excluding.intercept(request, ByteArray(0), answering()).consumeAndClose()

            // Then
            assertThat(log.events).isEmpty()
            assertThat(request.headers.getFirst(properties.correlationIdHeader)).isNull()
        }

        @Test
        fun `should be active only for paths matching an include pattern and let an exclude win`() {
            // Given: /api/** included, /api/internal excluded
            val scoped = interceptorWith(properties.copy(includePathPatterns = listOf("/api/**"), excludePathPrefixes = listOf("/api/internal")))

            // When: three calls
            scoped.intercept(request(uri = "https://h/api/things"), ByteArray(0), answering()).consumeAndClose()
            scoped.intercept(request(uri = "https://h/static/logo.png"), ByteArray(0), answering()).consumeAndClose()
            scoped.intercept(request(uri = "https://h/api/internal/jobs"), ByteArray(0), answering()).consumeAndClose()

            // Then: exactly the included one
            assertThat(log.events).hasSize(1)
            assertThat(keyValues(log.events.single())).containsEntry("adapter_url_path", "/api/things")
        }

        @Test
        fun `should match activation on the decoded path segments so an encoded variant cannot slip past an exclude`() {
            // What is tested: activation sees the path the way a server router would - segments decoded
            //   for matching, once.
            // Success criteria: `/%61pi/things` is included by /api/** and logs the raw path;
            //   `/api%2Fthings` is NOT (one segment "api/things"); `/%61ctuator/health` is excluded.
            // Why it matters: an exclude that an encoded spelling bypasses is not an exclude.
            // Given
            val scoped = interceptorWith(properties.copy(includePathPatterns = listOf("/api/**"), excludePathPrefixes = listOf("/actuator/health")))

            // When
            scoped.intercept(request(uri = "https://h/%61pi/things"), ByteArray(0), answering()).consumeAndClose()
            scoped.intercept(request(uri = "https://h/api%2Fthings"), ByteArray(0), answering()).consumeAndClose()
            scoped.intercept(request(uri = "https://h/%61ctuator/health"), ByteArray(0), answering()).consumeAndClose()

            // Then
            assertThat(log.events).hasSize(1)
            assertThat(keyValues(log.events.single())).containsEntry("adapter_url_path", "/%61pi/things")
        }

        @Test
        fun `should reject an invalid include pattern at construction time`() {
            // Given/When
            val thrown = catchThrowable { interceptorWith(properties.copy(includePathPatterns = listOf("/api/{unclosed"))) }

            // Then: the PARSER's exception, naming the malformed pattern
            assertThat(thrown).isInstanceOf(PatternParseException::class.java)
            assertThat((thrown as PatternParseException).toDetailedString()).contains("/api/{unclosed")
        }

        @Test
        fun `should announce the call before the wire call when enabled`() {
            // Given: start-line logging and an execution that observes the log stream mid-flight
            val startLogging = interceptorWith(properties.copy(logRequestStart = true))
            var eventsAtCallTime = listOf<String>()
            val execution =
                ClientHttpRequestExecution { _, _ ->
                    eventsAtCallTime = log.events.map { it.formattedMessage }
                    MockClientHttpResponse(ByteArray(0), HttpStatus.OK)
                }

            // When
            startLogging.intercept(request(method = HttpMethod.POST), ByteArray(0), execution).consumeAndClose()

            // Then: the arrival line was already visible during the call; only the completion line
            //   carries the outcome; the arrival line carries the identity in its MDC
            assertThat(eventsAtCallTime)
                .containsExactly("Adapter http exchange started POST https://api.example.com/things [adapter_request_id=generated-42]")
            assertThat(log.events).hasSize(2)
            assertThat(keyValues(log.events.first())).doesNotContainKey("adapter_outcome").containsEntry("adapter_url_host", "api.example.com")
            assertThat(log.events.first().mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "generated-42")
            assertThat(keyValues(log.events.last())).containsEntry("adapter_outcome", "success")
        }
    }

    @Nested
    inner class `Read failures, errors and re-entries` {
        @Test
        fun `should classify a body that could not even be opened as a failure with the received status`() {
            // What is tested: the engine call that OPENS the body stream (getBody throws IOException) is
            //   guarded like a read - not only the reads on the stream.
            // Success criteria: the exception reaches the application unchanged; at close the event is
            //   ERROR, outcome failure, WITH the 200 that was received and the cause attached.
            // Why it matters: a body that failed to open logged as success is wrong on exactly the calls
            //   the outcome exists for.
            // Given: a response whose body stream cannot be opened
            val unopenable =
                ClientHttpRequestExecution { _, _ ->
                    object : MockClientHttpResponse("late".toByteArray(), HttpStatus.OK) {
                        override fun getBody(): InputStream = throw IOException("connection dropped before the body")
                    }
                }
            val response = interceptor.intercept(request(), ByteArray(0), unopenable)

            // When: the application opens the body and the client closes the response in its finally
            val thrown = catchThrowable { response.body }
            response.close()

            // Then
            assertThat(thrown).isInstanceOf(IOException::class.java).hasMessageContaining("before the body")
            val event = log.events.single()
            assertThat(event.level).isEqualTo(Level.ERROR)
            assertThat(keyValues(event)).containsEntry("adapter_outcome", "failure").containsEntry("adapter_response_status_code", 200)
            assertThat(event.throwableProxy.message).contains("before the body")
        }

        @Test
        fun `should classify an unchecked exception thrown while reading the body as a failure`() {
            // What is tested: the read guard covers ANY exception, not only IOException - an engine's
            //   unchecked wrapper (UncheckedIOException, a runtime decoding error) is a failed read too.
            // Success criteria: rethrown unchanged; ERROR with outcome failure at close.
            // Why it matters: an engine that wraps its I/O errors would otherwise log every dropped
            //   connection as a success.
            // Given: a body stream that throws unchecked mid-read
            val broken =
                ClientHttpRequestExecution { _, _ ->
                    object : MockClientHttpResponse("x".toByteArray(), HttpStatus.OK) {
                        override fun getBody(): InputStream =
                            object : InputStream() {
                                override fun read(): Int = throw java.io.UncheckedIOException(IOException("reset"))
                            }
                    }
                }
            val response = interceptor.intercept(request(), ByteArray(0), broken)

            // When
            val thrown = catchThrowable { response.body.read() }
            response.close()

            // Then
            assertThat(thrown).isInstanceOf(java.io.UncheckedIOException::class.java)
            assertThat(keyValues(log.events.single())).containsEntry("adapter_outcome", "failure")
        }

        @Test
        fun `should close the gauge without an event when the wire call dies with an Error`() {
            // What is tested: the Throwable boundary decided in FailOpenDiagnostics - an Error from the
            //   execution (an inner interceptor's AssertionError, a LinkageError in the engine) is outside
            //   the fail-open promise, but the open-exchange gauge must not drift over it.
            // Success criteria: the Error propagates unchanged, no exchange event is emitted, the gauge is
            //   back at zero.
            // Why it matters: a permanently open exchange on the gauge is a false "never closed" baseline
            //   - the liveness signal crying wolf forever.
            // Given/When
            val thrown = catchThrowable { interceptor.intercept(request(), ByteArray(0)) { _, _ -> throw AssertionError("inner interceptor bug") } }

            // Then
            assertThat(thrown).isInstanceOf(AssertionError::class.java)
            assertThat(log.events).isEmpty()
            assertThat(meterRegistry.get(ClientLoggingMetrics.OPEN_EXCHANGES_METER).gauge().value()).isZero()
        }

        @Test
        fun `should keep counting the id as generated when a retrying outer interceptor re-enters with it`() {
            // What is tested: the origin counter across re-entries - attempt 1 generated and stamped the
            //   header, attempt 2 finds that header on the SAME request.
            // Success criteria: both attempts count `generated`, none `header`; the id is the same on both.
            // Why it matters: counting the module's own write as propagation would dilute the very signal
            //   the counter exists for (a rising `generated` share).
            // Given: one request re-entering twice, as an outer retry does
            val request = request()

            // When
            interceptor.intercept(request, ByteArray(0), answering()).consumeAndClose()
            interceptor.intercept(request, ByteArray(0), answering()).consumeAndClose()

            // Then
            fun origin(source: String) =
                meterRegistry
                    .get(ClientLoggingMetrics.CORRELATION_METER)
                    .tag("source", source)
                    .counter()
                    .count()
            assertThat(origin(ClientLoggingMetrics.REQUEST_ID_SOURCE_GENERATED)).isEqualTo(2.0)
            assertThat(origin(ClientLoggingMetrics.REQUEST_ID_SOURCE_HEADER)).isZero()
            assertThat(log.events.map { it.mdcPropertyMap[MdcKeys.REQUEST_ID] }).containsExactly("generated-42", "generated-42")
        }

        @Test
        fun `should replace a correlation header outside the acceptance rule with a generated id`() {
            // What is tested: the CorrelationHeader rule at the interceptor - a value with control
            //   characters (or over-long, or non-ASCII) counts as absent.
            // Success criteria: the generated id goes on the wire INSTEAD of the foreign value, the event
            //   and the MDC carry the generated id, the origin counts `generated`.
            // Why it matters: the value lands verbatim in the message and the MDC of every line of the
            //   call - a CR/LF in it forges lines in every plain-text sink.
            // Given: a request carrying a forged correlation id
            val request = request().apply { headers.set("X-Correlation-Id", "abc\r\nforged=line") }

            // When
            interceptor.intercept(request, ByteArray(0), answering()).consumeAndClose()

            // Then
            assertThat(request.headers.getFirst("X-Correlation-Id")).isEqualTo("generated-42")
            val event = log.events.single()
            assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "generated-42")
            assertThat(event.formattedMessage).doesNotContain("forged")
            assertThat(
                meterRegistry
                    .get(ClientLoggingMetrics.CORRELATION_METER)
                    .tag("source", "generated")
                    .counter()
                    .count(),
            ).isEqualTo(1.0)
        }
    }
}
