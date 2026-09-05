package eu.inqudium.legatium.restclient.logging

import ch.qos.logback.classic.Level
import eu.inqudium.legatium.common.ClientLoggingMetrics
import eu.inqudium.legatium.common.MdcKeys
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * The engine-agnosticism contract of the blocking twin, run once per request factory Spring ships (the
 * JDK `HttpClient`, Apache HttpComponents 5, Jetty, Reactor Netty, `HttpURLConnection`): the
 * interceptor touches an engine only through `ClientHttpRequestInterceptor` and the response tee on the
 * engine's own stream, so what this contract pins is that those seams hold against each real engine -
 * the body tee and its read state, the wire correlation header, the exception types each engine raises
 * for its CONNECT and READ timeouts as the shared `Timeouts` classification recognises them, wrapped in
 * `RestClient`'s `ResourceAccessException` - and how each engine hands a gzip answer to the
 * application, which is where the engines genuinely differ: some decompress transparently (and strip
 * the `Content-Length` the capture would otherwise trust), some hand the compressed bytes through. A
 * refused connection is the control: a failure that must NOT read as a timeout.
 *
 * The reactive twin's `ConnectorContract` is the model; a subclass supplies the factory and declares
 * whether its engine decompresses, every scenario, assertion and expected line is the same.
 */
@SpringBootTest(
    classes = [IntegrationApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "adapter-logging.log-request-body=always",
        "adapter-logging.log-response-body=always",
        "adapter-logging.measure-response-body-size=true",
        "adapter-logging.request-headers.includes=X-Correlation-Id",
        "adapter-logging.request-headers.unmasked=X-Correlation-Id",
        // Traceless calls, as in the interceptor integration test: the bridge is excluded so the
        // correlation contract is what goes on the wire.
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.micrometer.tracing.brave.autoconfigure.BraveAutoConfiguration," +
            "org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration",
    ],
)
abstract class RequestFactoryContract {
    /** The engine under test, built with the given connect and read timeouts. */
    protected abstract fun requestFactory(
        connectTimeout: Duration,
        readTimeout: Duration,
    ): ClientHttpRequestFactory

    /**
     * Whether this engine decodes a `Content-Encoding: gzip` answer before handing it to the application
     * - the one observable difference between the engines this contract pins instead of hiding.
     */
    protected abstract val decompressesTransparently: Boolean

    @Autowired
    private lateinit var restClientBuilder: RestClient.Builder

    @Autowired
    private lateinit var registry: MeterRegistry

    private lateinit var log: CapturedLogger
    private val closeables = mutableListOf<AutoCloseable>()

    /** Registers an engine resource to be released after the test. */
    protected fun <T : AutoCloseable> closing(resource: T): T = resource.also { closeables += it }

    @BeforeEach
    fun setUp() {
        log = CapturedLogger("adapter-http-exchange")
        peer.received.clear()
    }

    @AfterEach
    fun tearDown() {
        log.detach()
        closeables.reversed().forEach { runCatching { it.close() } }
        closeables.clear()
    }

    /** A client on this engine; [timeout] applies to connect AND read - SHORT only where the timeout is the subject. */
    private fun client(
        baseUrl: String,
        timeout: Duration = GENEROUS,
    ): RestClient =
        restClientBuilder
            .baseUrl(baseUrl)
            .requestFactory(requestFactory(connectTimeout = timeout, readTimeout = timeout))
            .build()

    private fun readStateCount(state: String): Double =
        registry
            .find(ClientLoggingMetrics.RESPONSE_BODY_READ_METER)
            .tags("uri", "${peer.baseUrl}/things/{id}", "host", peer.host, "state", state)
            .counter()
            ?.count() ?: 0.0

    @Test
    fun `should tee both bodies and send the correlation header through this request factory`() {
        // What is tested: the seams that touch the engine - the request body the interceptor is handed,
        //   the response tee on the engine's own stream (including the EOF or Content-Length observation
        //   that marks the read complete) - and the header the interceptor added to the request the
        //   engine sent.
        // Success criteria: the peer saw the correlation header and the body; one INFO event carries
        //   template, both bodies and the id, format-identical across engines; the read-state counter
        //   for this peer shows exactly one complete read.
        // Why it matters: an engine whose stream never returned the EOF the tee waits for, or that
        //   handed out a body the converter reads differently, would log a partial read or empty bodies
        //   without any other symptom.
        // Given/When
        val body =
            client(peer.baseUrl)
                .post()
                .uri("/things/{id}", 7)
                .contentType(MediaType.TEXT_PLAIN)
                .body("hello")
                .retrieve()
                .body(String::class.java)

        // Then
        assertThat(body).isEqualTo("""{"id":7,"echo":"hello"}""")
        val received = peer.received.single()
        assertThat(received.header("X-Correlation-Id")).isNotBlank()
        assertThat(received.body).isEqualTo("hello")
        val event = log.events.single()
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
        assertThat(readStateCount("complete")).describedAs("complete reads").isEqualTo(1.0)
        assertThat(readStateCount("partial")).describedAs("partial reads").isZero()
    }

    @Test
    fun `should log a gzip answer the way this request factory hands it to the application`() {
        // What is tested: a `Content-Encoding: gzip` answer with the COMPRESSED length declared - the
        //   tee mirrors what the engine delivers, and the Content-Encoding rule keeps the capture from
        //   trusting a declared length the engine may have made meaningless by decompressing.
        // Success criteria: one INFO success event with status 200; when the engine decompresses
        //   transparently, application and log both see the plaintext; otherwise both see the
        //   compressed bytes and the plaintext appears nowhere on the line.
        // Why it matters: the engines differ here, and an operator reading `adapter_response_body`
        //   must know which of the two a given engine produces - the contract pins it per engine
        //   instead of letting a deployment discover it.
        // Given/When
        val body =
            client(peer.baseUrl)
                .get()
                .uri("/gzip")
                .retrieve()
                .body(String::class.java)

        // Then
        val event = log.events.single()
        assertThat(event.level).isEqualTo(Level.INFO)
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "success").containsEntry("adapter_response_status_code", 200)
        val logged = keyValues(event)["adapter_response_body"].toString()
        if (decompressesTransparently) {
            assertThat(body).isEqualTo(PeerServer.GZIP_PLAINTEXT)
            assertThat(logged).isEqualTo(PeerServer.GZIP_PLAINTEXT)
        } else {
            assertThat(body).isNotEqualTo(PeerServer.GZIP_PLAINTEXT)
            assertThat(logged).doesNotContain("compressed hello")
        }
    }

    @Test
    fun `should log a 5xx answer through this request factory as WARN failure with the body`() {
        // What is tested: a peer answer the client turns into an exception - the response is still
        //   closed by the client, so the exchange completes with the status and the body it read.
        // Success criteria: the client throws HttpServerErrorException; the single event is WARN with
        //   outcome failure, status 500 and the body "boom".
        // Why it matters: the 5xx path runs the engine's stream through the exception's body read; an
        //   engine that closed the stream early would lose the body on the one line that needs it.
        // Given/When
        val thrown =
            catchThrowable {
                client(peer.baseUrl)
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
    fun `should log this request factory's read timeout as WARN timeout`() {
        // What is tested: the classification against the exception this engine really raises when the
        //   peer's answer does not arrive in time, wrapped in RestClient's ResourceAccessException.
        // Success criteria: against a peer answering after 1.5 s with a 200 ms read timeout, the call
        //   throws ResourceAccessException and the single event is WARN with outcome timeout and no
        //   status.
        // Why it matters: the timeout types differ per engine; only the real engine proves the list in
        //   Timeouts covers it through the client's wrapper.
        // Given/When
        val thrown =
            catchThrowable {
                client(peer.baseUrl, timeout = SHORT)
                    .get()
                    .uri("/slow")
                    .retrieve()
                    .body(String::class.java)
            }

        // Then
        assertThat(thrown).isInstanceOf(ResourceAccessException::class.java)
        val event = log.events.single()
        assertThat(event.level).describedAs("event for %s", thrown).isEqualTo(Level.WARN)
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "timeout").doesNotContainKey("adapter_response_status_code")
    }

    @Test
    fun `should log this request factory's connect timeout as WARN timeout`() {
        // What is tested: the classification against the exception this engine raises when the TCP
        //   connect never completes - for some engines a different type than its read timeout.
        // Success criteria: against a tarpit that never completes the handshake, with a 200 ms connect
        //   timeout, the single event is WARN with outcome timeout, `-> -` and no status.
        // Why it matters: an unreachable peer is a timeout to an operator, not a refusal; the two
        //   dispositions call for different reactions.
        // Given
        val tarpit = closing(Tarpit())

        // When
        val thrown =
            catchThrowable {
                client(tarpit.baseUrl, timeout = SHORT)
                    .get()
                    .uri("/things/1")
                    .retrieve()
                    .body(String::class.java)
            }

        // Then
        assertThat(thrown).isInstanceOf(ResourceAccessException::class.java)
        val event = log.events.single()
        assertThat(event.level).describedAs("event for %s", thrown).isEqualTo(Level.WARN)
        assertThat(event.formattedMessage).startsWith("Adapter http exchange GET ${tarpit.baseUrl}/things/1 -> - [")
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "timeout").doesNotContainKey("adapter_response_status_code")
    }

    @Test
    fun `should log a refused connection through this request factory as ERROR failure and not as a timeout`() {
        // What is tested: the control - a connection the peer actively refuses must stay a failure,
        //   whatever type this engine wraps it in.
        // Success criteria: ResourceAccessException; ERROR, outcome failure, `-> -`, no status, the
        //   cause attached.
        // Why it matters: a refusal misread as a timeout would send an operator looking for a slow
        //   peer that is in fact down.
        // Given/When
        val thrown =
            catchThrowable {
                client("http://127.0.0.1:1")
                    .get()
                    .uri("/things/1")
                    .retrieve()
                    .body(String::class.java)
            }

        // Then
        assertThat(thrown).isInstanceOf(ResourceAccessException::class.java)
        val event = log.events.single()
        assertThat(event.level).describedAs("event for %s", thrown).isEqualTo(Level.ERROR)
        assertThat(event.formattedMessage).startsWith("Adapter http exchange GET http://127.0.0.1:1/things/1 -> - [")
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "failure").doesNotContainKey("adapter_response_status_code")
        assertThat(event.throwableProxy).isNotNull()
    }

    companion object {
        private val SHORT: Duration = Duration.ofMillis(200)

        /** For every scenario whose subject is not the timeout: a loaded runner must not turn a tee test into a timeout test. */
        private val GENEROUS: Duration = Duration.ofSeconds(10)
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
