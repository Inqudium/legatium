package eu.inqudium.legatium.restclient.logging

import eu.inqudium.legatium.common.BodyReadState
import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.CorrelationIdGenerator
import eu.inqudium.legatium.common.HeaderLogProperties
import eu.inqudium.legatium.common.NanoTimeSource
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.mock.http.client.MockClientHttpResponse
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
            assertThat(rendered).contains("Authorization:\"${HeaderLogProperties.mask("Bearer secret-token")}\"")
            assertThat(rendered).doesNotContain("secret-token")
            assertThat(rendered).contains("X-Correlation-Id:\"generated-42\"")
        }

        @Test
        fun `should log the selected response headers as the peer sent them`() {
            // Given: a wildcard include with one exclusion
            val interceptor =
                interceptorWith(base.copy(responseHeaders = HeaderLogProperties(includes = listOf("*"), excludes = listOf("Set-Cookie"))))
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
            val interceptor = interceptorWith(base.copy(logRequestBody = true))
            val request = request(method = HttpMethod.POST).apply { headers.contentType = MediaType.APPLICATION_JSON }

            // When
            interceptor.intercept(request, """{"name":"thing"}""".toByteArray(), answering()).consumeAndClose()

            // Then
            assertThat(keyValues(log.events.single())).containsEntry("client_request_body", """{"name":"thing"}""")
        }

        @Test
        fun `should truncate the logged request body at the capture limit and say so`() {
            // Given: a 4-byte cap over a 10-byte body
            val interceptor = interceptorWith(base.copy(logRequestBody = true, maxBodyBytes = 4))

            // When
            interceptor.intercept(request(method = HttpMethod.POST), "0123456789".toByteArray(), answering()).consumeAndClose()

            // Then
            assertThat(keyValues(log.events.single())).containsEntry("client_request_body", "0123... [truncated, 10 bytes total]")
        }

        @Test
        fun `should decode the request body with the declared charset`() {
            // Given: an ISO-8859-1 body declared as such
            val interceptor = interceptorWith(base.copy(logRequestBody = true))
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
            interceptorWith(base.copy(logRequestBody = true)).intercept(request(), ByteArray(0), answering()).consumeAndClose()

            // Then
            assertThat(keyValues(log.events.single())).doesNotContainKey("client_request_body")
        }
    }

    @Nested
    inner class `Response body tee` {
        @Test
        fun `should log the response body the application actually read`() {
            // Given
            val interceptor = interceptorWith(base.copy(logResponseBody = true))

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
            interceptorWith(base.copy(logResponseBody = true)).intercept(request(), ByteArray(0), answering(body = "unread")).close()

            // Then
            assertThat(keyValues(log.events.single())).doesNotContainKey("client_response_body")
        }

        @Test
        fun `should log exactly the prefix the application read of a partially consumed body`() {
            // Given: the application reads 3 bytes and closes
            val response = interceptorWith(base.copy(logResponseBody = true)).intercept(request(), ByteArray(0), answering(body = "abcdef"))
            val prefix = ByteArray(3)
            response.body.read(prefix)
            response.close()

            // When/Then: what flowed, with the truthful count - not Content-Length
            assertThat(keyValues(log.events.single())).containsEntry("client_response_body", "abc")
        }

        @Test
        fun `should truncate the logged response body at the capture limit and keep the exact total`() {
            // Given
            val interceptor = interceptorWith(base.copy(logResponseBody = true, maxBodyBytes = 4))

            // When
            interceptor.intercept(request(), ByteArray(0), answering(body = "0123456789")).consumeAndClose()

            // Then
            assertThat(keyValues(log.events.single())).containsEntry("client_response_body", "0123... [truncated, 10 bytes total]")
        }

        @Test
        fun `should decode the response body with the charset the peer declared`() {
            // Given: a Latin-1 answer declared as such
            val interceptor = interceptorWith(base.copy(logResponseBody = true))
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
}
