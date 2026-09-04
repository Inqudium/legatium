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
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

/**
 * The connector-agnosticism contract, run once per connector Spring ships (Reactor Netty, the JDK
 * `HttpClient`, Jetty, Apache HttpComponents 5): the module touches a connector only through
 * `ExchangeFilterFunction` and the `ClientHttpRequestDecorator` tee, so what this contract pins is that
 * those seams hold against each real engine - the body tees on the engine's own buffers, the wire
 * correlation header - and that the exception types each engine raises for its CONNECT and RESPONSE
 * timeouts are the ones the shared `Timeouts` classification recognises. A refused connection is the
 * control: a failure that must NOT read as a timeout.
 *
 * A subclass supplies the engine; every scenario, assertion and expected line is the same.
 */
@SpringBootTest(
    classes = [IntegrationApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "adapter-logging.log-request-body=always",
        "adapter-logging.log-response-body=always",
        "adapter-logging.request-headers.includes=X-Correlation-Id",
        "adapter-logging.request-headers.unmasked=X-Correlation-Id",
        // Traceless calls, as in the Reactor Netty suite: the bridge is excluded so the correlation
        // contract is what goes on the wire.
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.micrometer.tracing.brave.autoconfigure.BraveAutoConfiguration," +
            "org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration",
    ],
)
abstract class ConnectorContract {
    /** The engine under test, built with the given connect and response timeouts. */
    protected abstract fun connector(
        connectTimeout: Duration,
        responseTimeout: Duration,
    ): ClientHttpConnector

    @Autowired
    private lateinit var webClientBuilder: WebClient.Builder

    private val logger = LoggerFactory.getLogger("http-adapter-exchange") as Logger
    private lateinit var appender: AwaitingAppender
    private val closeables = mutableListOf<AutoCloseable>()

    /** Registers an engine resource to be released after the test. */
    protected fun <T : AutoCloseable> closing(resource: T): T = resource.also { closeables += it }

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
        closeables.reversed().forEach { runCatching { it.close() } }
        closeables.clear()
    }

    private fun client(baseUrl: String): WebClient =
        webClientBuilder
            .baseUrl(baseUrl)
            .clientConnector(connector(connectTimeout = SHORT, responseTimeout = SHORT))
            .build()

    @Test
    fun `should tee both bodies and send the correlation header through this connector`() {
        // What is tested: the two seams that touch the engine - the request tee wrapping the engine's
        //   ClientHttpRequest as the inserter writes, the response tee on the engine's body buffers -
        //   and the header the filter added to the request the engine sent.
        // Success criteria: the peer saw the correlation header and the body; one INFO event carries
        //   template, both bodies and the id, format-identical across connectors.
        // Why it matters: a connector that bypassed the decorator or handed out buffers the tee cannot
        //   read would log empty bodies without any other symptom.
        // Given/When
        val body =
            client(peer.baseUrl)
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
        assertThat(received.body).isEqualTo("hello")
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.INFO)
        assertThat(event.formattedMessage)
            .isEqualTo("Adapter http exchange POST ${peer.baseUrl}/things/7 -> 200 [adapter_request_id=${received.header("X-Correlation-Id")}]")
        assertThat(keyValues(event))
            .containsEntry("adapter_outcome", "success")
            .containsEntry("adapter_url_template", "${peer.baseUrl}/things/{id}")
            .containsEntry("adapter_response_status_code", 200)
            .containsEntry("adapter_request_body", "hello")
            .containsEntry("adapter_response_body", """{"id":7,"echo":"hello"}""")
        assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, received.header("X-Correlation-Id"))
    }

    @Test
    fun `should log this connector's response timeout as WARN timeout`() {
        // What is tested: the classification against the exception this engine really raises when the
        //   peer's status line does not arrive in time.
        // Success criteria: against a peer answering after 1.5 s with a 200 ms response timeout, the
        //   call errors and the single event is WARN with outcome timeout and no status.
        // Why it matters: the timeout names differ per engine; only the real engine proves the list.
        // Given/When
        val thrown =
            catchThrowable {
                client(peer.baseUrl)
                    .get()
                    .uri("/slow")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .block()
            }

        // Then
        assertThat(thrown).isNotNull()
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).describedAs("event for %s", thrown).isEqualTo(Level.WARN)
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "timeout").doesNotContainKey("adapter_response_status_code")
    }

    @Test
    fun `should log this connector's connect timeout as WARN timeout`() {
        // What is tested: the classification against the exception this engine raises when the TCP
        //   connect never completes - a type that is NOT the same as its response timeout (Netty's
        //   ConnectTimeoutException extends ConnectException, not its TimeoutException).
        // Success criteria: against a tarpit that never completes the handshake, with a 200 ms connect
        //   timeout, the single event is WARN with outcome timeout, `-> -` and no status.
        // Why it matters: an unreachable peer is a timeout to an operator, not a refusal; the two
        //   dispositions call for different reactions.
        // Given
        val tarpit = closing(Tarpit())

        // When
        val thrown =
            catchThrowable {
                client(tarpit.baseUrl)
                    .get()
                    .uri("/things/1")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .block()
            }

        // Then
        assertThat(thrown).isNotNull()
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).describedAs("event for %s", thrown).isEqualTo(Level.WARN)
        assertThat(event.formattedMessage).startsWith("Adapter http exchange GET ${tarpit.baseUrl}/things/1 -> - [")
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "timeout").doesNotContainKey("adapter_response_status_code")
    }

    @Test
    fun `should log a refused connection through this connector as ERROR failure and not as a timeout`() {
        // What is tested: the control - a connection the peer actively refuses must stay a failure,
        //   whatever type this engine wraps it in.
        // Success criteria: ERROR, outcome failure, `-> -`, no status.
        // Given/When
        val thrown =
            catchThrowable {
                client("http://127.0.0.1:1")
                    .get()
                    .uri("/things/1")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .block()
            }

        // Then
        assertThat(thrown).isNotNull()
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).describedAs("event for %s", thrown).isEqualTo(Level.ERROR)
        assertThat(event.formattedMessage).startsWith("Adapter http exchange GET http://127.0.0.1:1/things/1 -> - [")
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "failure").doesNotContainKey("adapter_response_status_code")
    }

    companion object {
        private val SHORT: Duration = Duration.ofMillis(200)
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
