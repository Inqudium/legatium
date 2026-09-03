package eu.inqudium.legatium.restclient.logging

import eu.inqudium.legatium.common.BodyLogMode
import eu.inqudium.legatium.common.BodyReadState
import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.CorrelationIdGenerator
import eu.inqudium.legatium.common.HeaderLogProperties
import eu.inqudium.legatium.common.HeaderValueMasker
import eu.inqudium.legatium.common.NanoTimeSource
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.mock.http.client.MockClientHttpResponse
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * Header selection/masking and the body tee of [ClientRequestLoggingInterceptor]: what reaches the
 * `client_request_headers` / `client_response_headers` / `client_request_body` / `client_response_body`
 * fields, and what the response tee observes about the application's consumption.
 */
class ClientRequestLoggingInterceptorBodyAndHeaderTest {
    private val ticker = AtomicLong(0)
    private val base = ClientLoggingProperties(loggerName = "http-client-exchange-body-header-test")
    private lateinit var log: CapturedLogger

    @BeforeEach
    fun setUp() {
        log = CapturedLogger(base.loggerName)
    }

    @AfterEach
    fun tearDown() {
        log.detach()
    }

    private fun interceptorWith(properties: ClientLoggingProperties) = ClientRequestLoggingInterceptor(properties, NanoTimeSource { ticker.get() }, CorrelationIdGenerator { "generated-42" }, SimpleMeterRegistry())

    @Nested
    inner class `Header selection and masking` {
        @Test
        fun `should log selected request headers multi-value, mask the configured ones stably and include the sent correlation header`() {
            // What is tested: selection at wiring time from the OUTGOING headers - after the correlation
            //   header was added - multi-value joining, and the stable masking fingerprint.
            // Success criteria: Accept is joined with ", ", Authorization is the length:hash fingerprint
            //   (never the plaintext), the generated correlation header appears because it went out.
            // Why it matters: what the line shows must be what the peer received.
            // Given
            val interceptor =
                interceptorWith(
                    base.copy(
                        requestHeaders =
                            HeaderLogProperties(
                                includes = listOf("Accept", "Authorization", "X-Correlation-Id"),
                                masked = listOf("authorization"),
                            ),
                    ),
                )
            val request =
                request().apply {
                    headers.add("Accept", "application/json")
                    headers.add("Accept", "text/plain")
                    headers.set("Authorization", "Bearer secret-token")
                }

            // When
            interceptor.intercept(request, ByteArray(0), answering()).consumeAndClose()

            // Then
            val rendered = keyValues(log.events.single())["client_request_headers"].toString()
            assertThat(rendered).contains("Accept:\"application/json, text/plain\"")
            assertThat(rendered).contains("Authorization:\"${HeaderValueMasker.DEFAULT.mask("Bearer secret-token")}\"")
            assertThat(rendered).doesNotContain("secret-token")
            assertThat(rendered).contains("X-Correlation-Id:\"generated-42\"")
        }

        @Test
        fun `should render masked values through a host-provided masker`() {
            // What is tested: the masker is an injected collaborator - the interceptor built with a host
            //   bean masks request AND response headers with it.
            // Success criteria: both selected, masked headers carry the host masker's output, never the
            //   plaintext and never the built-in fingerprint.
            // Why it matters: a compliance regime forbidding unkeyed hashes must be satisfiable without
            //   forking the module.
            // Given
            val keyed = HeaderValueMasker { "hmac:${it.length}" }
            val keyedSubject =
                ClientRequestLoggingInterceptor(
                    base.copy(
                        requestHeaders = HeaderLogProperties(includes = listOf("Authorization"), masked = listOf("Authorization")),
                        responseHeaders = HeaderLogProperties(includes = listOf("Set-Cookie"), masked = listOf("Set-Cookie")),
                    ),
                    NanoTimeSource { ticker.get() },
                    CorrelationIdGenerator { "generated-42" },
                    SimpleMeterRegistry(),
                    keyed,
                )
            val request = request().apply { headers.set("Authorization", "Bearer secret-token") }

            // When
            keyedSubject.intercept(request, ByteArray(0), answering { it.headers.set("Set-Cookie", "session=1") }).consumeAndClose()

            // Then
            val fields = keyValues(log.events.single())
            assertThat(fields["client_request_headers"].toString()).isEqualTo("[Authorization:\"hmac:19\"]")
            assertThat(fields["client_response_headers"].toString()).isEqualTo("[Set-Cookie:\"hmac:9\"]")
        }

        @Test
        fun `should log the selected response headers as the peer sent them`() {
            // Given: a wildcard include with one exclusion and one name allowed in plaintext
            val interceptor =
                interceptorWith(
                    base.copy(
                        responseHeaders = HeaderLogProperties(includes = listOf("*"), excludes = listOf("Set-Cookie"), unmasked = listOf("Content-Type")),
                    ),
                )
            val execution =
                answering(body = "ok") { response ->
                    response.headers.set("Content-Type", "text/plain")
                    response.headers.add("Set-Cookie", "session=1")
                    response.headers.add("Set-Cookie", "theme=dark")
                }

            // When
            interceptor.intercept(request(), ByteArray(0), execution).consumeAndClose()

            // Then
            val rendered = keyValues(log.events.single())["client_response_headers"].toString()
            assertThat(rendered).contains("Content-Type:\"text/plain\"")
            assertThat(rendered).doesNotContain("Set-Cookie")
        }

        @Test
        fun `should mask every selected header by default so a wildcard include never leaks plaintext`() {
            // What is tested: ADR-0005 at the interceptor - the documented debugging move
            //   `includes: ["*"]` with nothing said about masking.
            // Success criteria: every logged request header is a fingerprint; the secret appears nowhere.
            // Why it matters: with masking as a second, empty list the same configuration logged
            //   everything in plaintext - the unsafe combination was the convenient one.
            // Given
            val interceptor = interceptorWith(base.copy(requestHeaders = HeaderLogProperties(includes = listOf("*"))))
            val request = request().apply { headers.set("Authorization", "Bearer secret-token") }

            // When
            interceptor.intercept(request, ByteArray(0), answering()).consumeAndClose()

            // Then
            val rendered = keyValues(log.events.single())["client_request_headers"].toString()
            assertThat(rendered).contains("Authorization:\"${HeaderValueMasker.DEFAULT.mask("Bearer secret-token")}\"")
            assertThat(rendered).doesNotContain("secret-token")
        }

        @Test
        fun `should omit the header fields when nothing is selected`() {
            // Given: the default sections
            interceptorWith(base).intercept(request().apply { headers.set("Accept", "*/*") }, ByteArray(0), answering()).consumeAndClose()

            // When/Then
            assertThat(keyValues(log.events.single())).doesNotContainKeys("client_request_headers", "client_response_headers")
        }
    }

    @Nested
    inner class `Request body` {
        @Test
        fun `should log the request body the client hands the interceptor`() {
            // Given
            val interceptor = interceptorWith(base.copy(logRequestBody = BodyLogMode.ALWAYS))
            val request = request(method = HttpMethod.POST).apply { headers.contentType = MediaType.APPLICATION_JSON }

            // When
            interceptor.intercept(request, """{"name":"thing"}""".toByteArray(), answering()).consumeAndClose()

            // Then
            assertThat(keyValues(log.events.single())).containsEntry("client_request_body", """{"name":"thing"}""")
        }

        @Test
        fun `should truncate the logged request body at the capture limit and say so`() {
            // Given: a 4-byte cap over a 10-byte body
            val interceptor = interceptorWith(base.copy(logRequestBody = BodyLogMode.ALWAYS, maxBodyBytes = 4))

            // When
            interceptor.intercept(request(method = HttpMethod.POST), "0123456789".toByteArray(), answering()).consumeAndClose()

            // Then
            assertThat(keyValues(log.events.single())).containsEntry("client_request_body", "0123... [truncated, 10 bytes total]")
        }

        @Test
        fun `should decode the request body with the declared charset`() {
            // Given: an ISO-8859-1 body declared as such
            val interceptor = interceptorWith(base.copy(logRequestBody = BodyLogMode.ALWAYS))
            val request =
                request(method = HttpMethod.POST).apply {
                    headers.contentType = MediaType.parseMediaType("text/plain; charset=ISO-8859-1")
                }

            // When
            interceptor.intercept(request, "café".toByteArray(StandardCharsets.ISO_8859_1), answering()).consumeAndClose()

            // Then
            assertThat(keyValues(log.events.single())).containsEntry("client_request_body", "café")
        }

        @Test
        fun `should omit the request body key for a bodiless request`() {
            // Given/When
            interceptorWith(base.copy(logRequestBody = BodyLogMode.ALWAYS)).intercept(request(), ByteArray(0), answering()).consumeAndClose()

            // Then
            assertThat(keyValues(log.events.single())).doesNotContainKey("client_request_body")
        }
    }

    @Nested
    inner class `Response body tee` {
        @Test
        fun `should log the response body the application actually read`() {
            // Given
            val interceptor = interceptorWith(base.copy(logResponseBody = BodyLogMode.ALWAYS))

            // When: the client reads the whole body, as its converters would
            val body = interceptor.intercept(request(), ByteArray(0), answering(body = "hello")).consumeAndClose()

            // Then
            assertThat(body).isEqualTo("hello")
            assertThat(keyValues(log.events.single())).containsEntry("client_response_body", "hello")
        }

        @Test
        fun `should omit the response body key when the application never opened the body`() {
            // What is tested: the tee's truthfulness - a body the application never read flows nowhere.
            // Success criteria: no client_response_body key, rather than an empty or fabricated value.
            // Why it matters: 'logged' must mean 'actually flowed'.
            // Given/When: a bodiless consumption (toBodilessEntity-style: close without reading)
            interceptorWith(base.copy(logResponseBody = BodyLogMode.ALWAYS)).intercept(request(), ByteArray(0), answering(body = "unread")).close()

            // Then
            assertThat(keyValues(log.events.single())).doesNotContainKey("client_response_body")
        }

        @Test
        fun `should log exactly the prefix the application read of a partially consumed body`() {
            // Given: the application reads 3 bytes and closes
            val response = interceptorWith(base.copy(logResponseBody = BodyLogMode.ALWAYS)).intercept(request(), ByteArray(0), answering(body = "abcdef"))
            val prefix = ByteArray(3)
            response.body.read(prefix)
            response.close()

            // When/Then: what flowed, with the truthful count - not Content-Length
            assertThat(keyValues(log.events.single())).containsEntry("client_response_body", "abc")
        }

        @Test
        fun `should truncate the logged response body at the capture limit and keep the exact total`() {
            // Given
            val interceptor = interceptorWith(base.copy(logResponseBody = BodyLogMode.ALWAYS, maxBodyBytes = 4))

            // When
            interceptor.intercept(request(), ByteArray(0), answering(body = "0123456789")).consumeAndClose()

            // Then
            assertThat(keyValues(log.events.single())).containsEntry("client_response_body", "0123... [truncated, 10 bytes total]")
        }

        @Test
        fun `should decode the response body with the charset the peer declared`() {
            // Given: a Latin-1 answer declared as such
            val interceptor = interceptorWith(base.copy(logResponseBody = BodyLogMode.ALWAYS))
            val latin = "caf\u00e9".toByteArray(StandardCharsets.ISO_8859_1)
            val latinExecution =
                ClientHttpRequestExecution { _, _ ->
                    MockClientHttpResponse(latin, HttpStatus.OK).also {
                        it.headers.contentType = MediaType.parseMediaType("text/plain; charset=ISO-8859-1")
                    }
                }

            // When
            interceptor.intercept(request(), ByteArray(0), latinExecution).consumeAndClose()

            // Then
            assertThat(keyValues(log.events.single())).containsEntry("client_response_body", "café")
        }

        @Test
        fun `should record the read state of the response body as unread, partial or complete`() {
            // What is tested: the observation points of the read state - opening the stream marks
            //   PARTIAL, observing EOF marks COMPLETE, never opening it leaves UNREAD.
            // Success criteria: three exchanges, three states, read off the captures.
            // Why it matters: the state is the one signal that tells a discarded response body from an
            //   absent one.
            // Given
            val capture = BoundedBodyCapture(8)
            val response =
                CapturingClientHttpResponse(
                    MockClientHttpResponse("ab".toByteArray(), HttpStatus.OK),
                    capture,
                    onReadFailure = {},
                    onClose = {},
                )

            // When/Then
            assertThat(capture.readState).isEqualTo(BodyReadState.UNREAD)
            val stream = response.body
            assertThat(capture.readState).isEqualTo(BodyReadState.PARTIAL)
            stream.read()
            assertThat(capture.readState).isEqualTo(BodyReadState.PARTIAL)
            stream.readAllBytes()
            assertThat(capture.readState).isEqualTo(BodyReadState.COMPLETE)
        }

        @Test
        fun `should not install a tee when neither logging nor measuring is on`() {
            // Given: the defaults - the response body must pass through untouched
            val body = interceptorWith(base).intercept(request(), ByteArray(0), answering(body = "raw")).consumeAndClose()

            // When/Then
            assertThat(body).isEqualTo("raw")
            assertThat(keyValues(log.events.single())).doesNotContainKey("client_response_body")
        }
    }

    @Nested
    inner class `Outcome-gated bodies` {
        private val onFailure = base.copy(logRequestBody = BodyLogMode.ON_FAILURE, logResponseBody = BodyLogMode.ON_FAILURE)

        @Test
        fun `should withhold both bodies from a successful exchange in on-failure mode`() {
            // What is tested: the volume switch - on-failure captures (the outcome is unknown while the
            //   bytes flow) and discards at emission when the outcome is success.
            // Success criteria: the application receives the response body; the line carries neither body.
            // Why it matters: this is the mode that keeps body logging affordable outside a debug session.
            // Given/When
            val body = interceptorWith(onFailure).intercept(request(method = HttpMethod.POST), "sent".toByteArray(), answering(body = "received")).consumeAndClose()

            // Then
            assertThat(body).isEqualTo("received")
            assertThat(keyValues(log.events.single()))
                .containsEntry("client_outcome", "success")
                .doesNotContainKeys("client_request_body", "client_response_body")
        }

        @Test
        fun `should log both bodies of a 5xx answer in on-failure mode`() {
            // Given/When: a failure outcome without an exception
            interceptorWith(onFailure)
                .intercept(request(method = HttpMethod.POST), "sent".toByteArray(), answering(status = HttpStatus.BAD_GATEWAY, body = "upstream down"))
                .consumeAndClose()

            // Then
            assertThat(keyValues(log.events.single()))
                .containsEntry("client_outcome", "failure")
                .containsEntry("client_request_body", "sent")
                .containsEntry("client_response_body", "upstream down")
        }

        @Test
        fun `should log the buffered request body of a call that threw in on-failure mode`() {
            // Given: a call that never gets a response
            val refused = ClientHttpRequestExecution { _, _ -> throw IOException("Connection refused") }

            // When
            val thrown = catchThrowable { interceptorWith(onFailure).intercept(request(method = HttpMethod.POST), "sent".toByteArray(), refused) }

            // Then: the request body that was captured before the outcome was known is on the line
            assertThat(thrown).isInstanceOf(IOException::class.java)
            assertThat(keyValues(log.events.single()))
                .containsEntry("client_outcome", "failure")
                .containsEntry("client_request_body", "sent")
                .doesNotContainKey("client_response_body")
        }

        @Test
        fun `should treat a 4xx answer as success and withhold the bodies in on-failure mode`() {
            // What is tested: on-failure follows the outcome vocabulary, not the status class - a 4xx is
            //   a success outcome (the peer answered; the request was wrong).
            // Success criteria: outcome success, and neither body on the line.
            // Why it matters: the gate must be predictable from the documented vocabulary; widening it is a
            //   change of the vocabulary, not a hidden special case in the body logic.
            // Given/When: a 404 with a body
            interceptorWith(onFailure)
                .intercept(request(method = HttpMethod.POST), "sent".toByteArray(), answering(status = HttpStatus.NOT_FOUND, body = "no such thing"))
                .consumeAndClose()

            // Then
            assertThat(keyValues(log.events.single()))
                .containsEntry("client_outcome", "success")
                .doesNotContainKeys("client_request_body", "client_response_body")
        }

        @Test
        fun `should still measure the size of a body it withholds`() {
            // Given: on-failure plus measuring, on an own registry
            val registry = SimpleMeterRegistry()
            val interceptor = ClientRequestLoggingInterceptor(onFailure.copy(measureRequestBodySize = true), { ticker.get() }, { "g" }, registry)

            // When: a successful call with a 4-byte request body
            interceptor.intercept(request(method = HttpMethod.POST), "four".toByteArray(), answering()).consumeAndClose()

            // Then: the sample is recorded, the field is not
            assertThat(registry.get(ClientLoggingMetrics.REQUEST_BODY_SIZE_METER).summary().totalAmount()).isEqualTo(4.0)
            assertThat(keyValues(log.events.single())).doesNotContainKey("client_request_body")
        }
    }
}
