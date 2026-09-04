package eu.inqudium.legatium.webclient.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import eu.inqudium.legatium.common.MdcKeys
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.Exceptions
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * End to end through Boot's auto-configured `WebClient.Builder` and a real Reactor Netty connector
 * against a real HTTP peer: registration by the customizer, the URI template attribute, the body tees
 * on pooled buffers, the correlation header on the wire, and the dispositions as the connector really
 * raises them (connection refused, the connector's response timeout, a downstream timeout operator).
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
class ClientRequestLoggingFilterIntegrationTest {
    @Autowired
    private lateinit var webClientBuilder: WebClient.Builder

    private val logger = LoggerFactory.getLogger("adapter-http-exchange") as Logger
    private lateinit var appender: AwaitingAppender

    @BeforeEach
    fun setUp() {
        appender = AwaitingAppender().apply { start() }
        logger.addAppender(appender)
        logger.level = Level.INFO
        peer.received.clear()
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `should log one complete event for a real call including template, headers and bodies`() {
        // What is tested: the full happy path through Boot's builder and Reactor Netty - the customizer
        //   attached the filter, the template attribute is recorded, both bodies are teed on pooled
        //   buffers, and the generated correlation header went out on the wire.
        // Success criteria: the peer saw the request with the correlation header; one INFO event with
        //   the client field family, format-identical to the RestClient twin.
        // Why it matters: only a real connector proves the buffer handling and the registration hold
        //   outside the stubs.
        // Given
        val client = webClientBuilder.baseUrl(peer.baseUrl).build()

        // When
        val body =
            client
                .post()
                .uri("/things/{id}", 7)
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue("hello")
                .retrieve()
                .bodyToMono(String::class.java)
                .block()

        // Then
        assertThat(body).isEqualTo("""{"id":7,"echo":"hello"}""")
        val received = peer.received.single()
        assertThat(received.header("X-Correlation-Id")).isNotBlank()
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.INFO)
        assertThat(event.formattedMessage)
            .isEqualTo("Adapter http exchange POST ${peer.baseUrl}/things/7 -> 200 [adapter_request_id=${received.header("X-Correlation-Id")}]")
        assertThat(keyValues(event))
            .containsEntry("adapter_outcome", "success")
            .containsEntry("adapter_url_host", peer.host)
            .containsEntry("adapter_url_path", "/things/7")
            .containsEntry("adapter_url_template", "${peer.baseUrl}/things/{id}")
            .containsEntry("adapter_response_status_code", 200)
            .containsEntry("adapter_request_body", "hello")
            .containsEntry("adapter_response_body", """{"id":7,"echo":"hello"}""")
        assertThat(keyValues(event)["adapter_response_headers"].toString()).contains("Content-Type:\"application/json\"")
        assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, received.header("X-Correlation-Id"))
    }

    @Test
    fun `should log a 5xx answer as WARN failure with the body the client read for its exception`() {
        // What is tested: the 5xx classification through a real Reactor Netty exchange -
        //   `retrieve()` reads the body to build its WebClientResponseException, and that read is
        //   what the tee observes.
        // Success criteria: the caller gets the response exception; the single event is WARN with
        //   outcome failure, status 500 and the response body "boom".
        // Why it matters: a 5xx is not an error signal in the reactive chain; the body on the line
        //   comes from the client's own error-handling read, which only a real connector exercises.
        // Given
        val client = webClientBuilder.baseUrl(peer.baseUrl).build()

        // When
        val thrown =
            catchThrowable {
                client
                    .get()
                    .uri("/fail")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .block()
            }

        // Then
        assertThat(thrown).isInstanceOf(WebClientResponseException::class.java)
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.WARN)
        assertThat(keyValues(event))
            .containsEntry("adapter_outcome", "failure")
            .containsEntry("adapter_response_status_code", 500)
            .containsEntry("adapter_response_body", "boom")
    }

    @Test
    fun `should log a refused connection as ERROR failure without a status`() {
        // What is tested: the no-response path against a real closed port - the connector errors
        //   before a status line, the exchange completes through doFinally on the response Mono.
        // Success criteria: the caller gets a WebClientRequestException; the single event is ERROR
        //   with outcome failure, "-> -" in the message and no status field.
        // Why it matters: a connection refused is the most common outage signature; the line must
        //   say so loudly and must not invent a status the peer never sent.
        // Given
        val client = webClientBuilder.baseUrl("http://127.0.0.1:1").build()

        // When
        val thrown =
            catchThrowable {
                client
                    .get()
                    .uri("/things/1")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .block()
            }

        // Then
        assertThat(thrown).isInstanceOf(WebClientRequestException::class.java)
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(event.formattedMessage).startsWith("Adapter http exchange GET http://127.0.0.1:1/things/1 -> - [")
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "failure").doesNotContainKey("adapter_response_status_code")
    }

    @Test
    fun `should log the connector's response timeout as WARN timeout`() {
        // What is tested: the timeout classification against the REAL connector's exception - Reactor
        //   Netty's ReadTimeoutException, recognised by name without a Netty dependency in the module.
        // Success criteria: with a 200 ms response timeout against a peer that answers after 1.5 s, the
        //   client errors and the single event is WARN with outcome timeout and no status.
        // Why it matters: only a real connector proves the names are the ones that actually occur.
        // Given
        val client =
            webClientBuilder
                .baseUrl(peer.baseUrl)
                .clientConnector(ReactorClientHttpConnector(HttpClient.create().responseTimeout(Duration.ofMillis(200))))
                .build()

        // When
        val thrown =
            catchThrowable {
                client
                    .get()
                    .uri("/slow")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .block()
            }

        // Then
        assertThat(thrown).isInstanceOf(WebClientRequestException::class.java)
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.WARN)
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "timeout").doesNotContainKey("adapter_response_status_code")
    }

    @Test
    fun `should log a downstream timeout operator as cancelled`() {
        // What is tested: the documented boundary - a `timeout()` the CALLER applies cancels the
        //   exchange; the filter sees a CANCEL, not an error.
        // Success criteria: the caller gets the TimeoutException, the single event is WARN with
        //   outcome cancelled and no status.
        // Why it matters: an operator reading `cancelled` must know it may be their own timeout.
        // Given
        val client = webClientBuilder.baseUrl(peer.baseUrl).build()

        // When
        val thrown =
            catchThrowable {
                client
                    .get()
                    .uri("/slow")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(Duration.ofMillis(200))
                    .block()
            }

        // Then: block() wraps the checked TimeoutException; the exchange itself was cancelled
        assertThat(Exceptions.unwrap(thrown)).isInstanceOf(TimeoutException::class.java)
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.WARN)
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "cancelled").doesNotContainKey("adapter_response_status_code")
    }

    @Test
    fun `should log a bodiless 204 without body fields`() {
        // What is tested: a real 204 answered by the peer with no body, consumed via
        //   toBodilessEntity - the release path on a body that yields no buffer.
        // Success criteria: status 204 on the entity and in the event; neither body key is present
        //   although both body modes are `always`.
        // Why it matters: a bodiless answer is the normal shape of a DELETE or PUT; an empty body
        //   field on each of them would be noise in the most frequent healthy line.
        // Given
        val client = webClientBuilder.baseUrl(peer.baseUrl).build()

        // When
        val entity =
            requireNotNull(
                client
                    .get()
                    .uri("/empty")
                    .retrieve()
                    .toBodilessEntity()
                    .block(),
            )

        // Then
        assertThat(entity.statusCode.value()).isEqualTo(204)
        val event = appender.awaitEvents(1).single()
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

/** The smallest Boot application that auto-configures the client and this module. */
@SpringBootConfiguration
@EnableAutoConfiguration
internal class IntegrationApp
