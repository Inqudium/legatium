package eu.inqudium.legatium.restclient.logging

import ch.qos.logback.classic.Level
import eu.inqudium.legatium.common.MdcKeys
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.restclient.RestTemplateBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.util.DefaultUriBuilderFactory
import java.time.Duration

/**
 * End to end through Boot's auto-configured `RestClient.Builder` and `RestTemplateBuilder` against a
 * real HTTP peer: registration by the customizers, the JDK HTTP engine, the URI template attribute, the
 * body tee on real streams, the correlation header on the wire, and the no-response dispositions
 * (connection refused, read timeout) as the engine really raises them.
 */
@SpringBootTest(
    classes = [IntegrationApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "adapter-logging.log-request-body=always",
        "adapter-logging.log-response-body=always",
        "adapter-logging.response-headers.includes=Content-Type",
        "adapter-logging.response-headers.unmasked=Content-Type",
        "adapter-logging.request-headers.includes=X-Correlation-Id",
        "adapter-logging.request-headers.unmasked=X-Correlation-Id",
        // The tracing bridge is on this test classpath for the tracing suite; excluded here, so the
        // calls are TRACELESS (an active bridge injects a traceparent into EVERY call, sampled or not)
        // and the correlation contract is what goes on the wire.
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.micrometer.tracing.brave.autoconfigure.BraveAutoConfiguration," +
            "org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration",
    ],
)
class ClientRequestLoggingInterceptorIntegrationTest {
    @Autowired
    private lateinit var restClientBuilder: RestClient.Builder

    @Autowired
    private lateinit var restTemplateBuilder: RestTemplateBuilder

    private lateinit var log: CapturedLogger

    @BeforeEach
    fun setUp() {
        log = CapturedLogger("http-adapter-exchange")
        peer.received.clear()
    }

    @AfterEach
    fun tearDown() {
        log.detach()
    }

    @Test
    fun `should log one complete event for a real call including template, headers and bodies`() {
        // What is tested: the full happy path through Boot's builder and the JDK engine - the
        //   customizer attached the interceptor, the template attribute is recorded, the response body
        //   is teed on a real stream, and the generated correlation header went out on the wire.
        // Success criteria: the peer saw the request with the correlation header; one INFO event with
        //   the client field family, format-identical to the WebClient twin.
        // Why it matters: only a real client and a real engine prove the registration and the stream
        //   handling hold outside the mocks.
        // Given
        val client = restClientBuilder.baseUrl(peer.baseUrl).build()

        // When
        val body =
            client
                .post()
                .uri("/things/{id}", 7)
                .contentType(MediaType.TEXT_PLAIN)
                .body("hello")
                .retrieve()
                .body(String::class.java)

        // Then: the peer got the envoy
        assertThat(body).isEqualTo("""{"id":7,"echo":"hello"}""")
        val received = peer.received.single()
        assertThat(received.path).isEqualTo("/things/7")
        assertThat(received.header("X-Correlation-Id")).isNotBlank()

        // And: one event describing exactly that
        val event = log.events.single()
        assertThat(event.level).isEqualTo(Level.INFO)
        assertThat(event.formattedMessage)
            .isEqualTo("Client http exchange POST ${peer.baseUrl}/things/7 -> 200 [adapter_request_id=${received.header("X-Correlation-Id")}]")
        assertThat(keyValues(event))
            .containsEntry("adapter_outcome", "success")
            .containsEntry("adapter_request_method", "POST")
            .containsEntry("adapter_url_host", peer.host)
            .containsEntry("adapter_url_path", "/things/7")
            .containsEntry("adapter_url_template", "${peer.baseUrl}/things/{id}")
            .containsEntry("adapter_response_status_code", 200)
            .containsEntry("adapter_request_body", "hello")
            .containsEntry("adapter_response_body", """{"id":7,"echo":"hello"}""")
        assertThat(keyValues(event)["adapter_response_headers"].toString()).contains("Content-Type:\"application/json\"")
        assertThat(keyValues(event)["adapter_request_headers"].toString()).contains("X-Correlation-Id:\"${received.header("X-Correlation-Id")}\"")
        assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, received.header("X-Correlation-Id"))
    }

    @Test
    fun `should log a 5xx answer as WARN failure with the body the client read for its exception`() {
        // Given
        val client = restClientBuilder.baseUrl(peer.baseUrl).build()

        // When: the default status handler turns the 500 into an exception - reading the body for it
        val thrown =
            catchThrowable {
                client
                    .get()
                    .uri("/fail")
                    .retrieve()
                    .body(String::class.java)
            }

        // Then
        assertThat(thrown).isInstanceOf(HttpServerErrorException::class.java)
        val event = log.events.single()
        assertThat(event.level).isEqualTo(Level.WARN)
        assertThat(keyValues(event))
            .containsEntry("adapter_outcome", "failure")
            .containsEntry("adapter_response_status_code", 500)
            .containsEntry("adapter_response_body", "boom")
    }

    @Test
    fun `should log a refused connection as ERROR failure without a status`() {
        // Given: a port nobody listens on
        val client = restClientBuilder.baseUrl("http://127.0.0.1:1").build()

        // When
        val thrown =
            catchThrowable {
                client
                    .get()
                    .uri("/things/1")
                    .retrieve()
                    .body(String::class.java)
            }

        // Then
        assertThat(thrown).isInstanceOf(ResourceAccessException::class.java)
        val event = log.events.single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(event.formattedMessage).startsWith("Client http exchange GET http://127.0.0.1:1/things/1 -> - [")
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "failure").doesNotContainKey("adapter_response_status_code")
        assertThat(event.throwableProxy).isNotNull()
    }

    @Test
    fun `should log a read timeout as WARN timeout the way the JDK engine raises it`() {
        // What is tested: the timeout classification against the REAL engine's exception type.
        // Success criteria: with a 200 ms read timeout against a peer that answers after 1.5 s, the
        //   client throws and the single event is WARN with outcome timeout and no status.
        // Why it matters: the classification walks the cause chain by type and name; only a real
        //   engine proves the names are the ones that actually occur.
        // Given
        val client =
            restClientBuilder
                .baseUrl(peer.baseUrl)
                .requestFactory(JdkClientHttpRequestFactory().apply { setReadTimeout(Duration.ofMillis(200)) })
                .build()

        // When
        val thrown =
            catchThrowable {
                client
                    .get()
                    .uri("/slow")
                    .retrieve()
                    .body(String::class.java)
            }

        // Then
        assertThat(thrown).isInstanceOf(ResourceAccessException::class.java)
        val event = log.events.single()
        assertThat(event.level).isEqualTo(Level.WARN)
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "timeout").doesNotContainKey("adapter_response_status_code")
    }

    @Test
    fun `should log a RestTemplate call through the same interceptor without a template`() {
        // Given: RestTemplate records no URI template attribute (the base URL goes in through the
        //   template handler - Boot 4 deprecated rootUri in favour of it)
        val restTemplate = restTemplateBuilder.uriTemplateHandler(DefaultUriBuilderFactory(peer.baseUrl)).build()

        // When
        val body = restTemplate.getForObject("/things/{id}", String::class.java, 9)

        // Then
        assertThat(body).isEqualTo("""{"id":9,"echo":""}""")
        val event = log.events.single()
        assertThat(keyValues(event))
            .containsEntry("adapter_url_path", "/things/9")
            .doesNotContainKey("adapter_url_template")
        assertThat(peer.received.single().header("X-Correlation-Id")).isNotBlank()
    }

    @Test
    fun `should log a bodiless 204 without body fields`() {
        // Given
        val client = restClientBuilder.baseUrl(peer.baseUrl).build()

        // When
        val entity =
            client
                .get()
                .uri("/empty")
                .retrieve()
                .toBodilessEntity()

        // Then
        assertThat(entity.statusCode.value()).isEqualTo(204)
        val event = log.events.single()
        assertThat(keyValues(event))
            .containsEntry("adapter_response_status_code", 204)
            .doesNotContainKeys("adapter_request_body", "adapter_response_body")
    }

    companion object {
        private lateinit var peer: PeerServer

        @JvmStatic
        @BeforeAll
        fun startPeer() {
            peer = PeerServer()
        }

        @JvmStatic
        @AfterAll
        fun stopPeer() {
            peer.close()
        }
    }
}

/** The smallest Boot application that auto-configures the clients and this module. */
@SpringBootConfiguration
@EnableAutoConfiguration
internal class IntegrationApp
