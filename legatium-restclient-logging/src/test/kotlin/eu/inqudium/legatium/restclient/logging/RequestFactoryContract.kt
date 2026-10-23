package eu.inqudium.legatium.restclient.logging

import ch.qos.logback.classic.Level
import eu.inqudium.legatium.common.ClientLoggingMetrics
import eu.inqudium.legatium.common.MdcKeys
import eu.inqudium.legatium.common.PeerServer
import eu.inqudium.legatium.common.Tarpit
import eu.inqudium.legatium.common.keyValues
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
 * refused connection is the control: a failure that must NOT read as a timeout. Three scenarios pin the
 * abandoned call as `cancelled` (ADR-0015): a caller interrupted mid-call on a virtual thread, the same
 * interrupt on a platform thread - which only an engine waiting on a future honours - and a worker
 * cancelled through its future, the TimeLimiter shape.
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
        TRACELESS_CALLS,
    ],
)
abstract class RequestFactoryContract : PeerIntegrationSuite() {
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

    /**
     * Whether an interrupt reaches this engine's blocking wait on a PLATFORM thread. An engine that
     * waits on a future (the JDK client, Jetty, Reactor Netty) wakes up on it; one that blocks in a
     * socket read (`HttpURLConnection`, Apache's classic client) does not - the JDK makes socket I/O
     * interruptible on virtual threads only - and finishes the exchange with the peer's answer.
     */
    protected abstract val interruptibleOnPlatformThreads: Boolean

    /** Boot's prototype builder - a fresh one per test instance; an engine suite's own scenario builds its client on it too. */
    @Autowired
    protected lateinit var restClientBuilder: RestClient.Builder

    @Autowired
    private lateinit var registry: MeterRegistry

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
        // Given: the counts before the call - the registry belongs to the cached context the engine
        //   suites share, so the assertion is a delta, not an absolute
        val completeBefore = readStateCount("complete")
        val partialBefore = readStateCount("partial")

        // When
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
        assertThat(readStateCount("complete") - completeBefore).describedAs("complete reads").isEqualTo(1.0)
        assertThat(readStateCount("partial") - partialBefore).describedAs("partial reads").isZero()
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
        // The raw value, not its toString: an absent key would render as the string "null" and pass
        // every "not the plaintext" check below.
        assertThat(keyValues(event)).containsKey("adapter_response_body")
        val logged = keyValues(event)["adapter_response_body"]
        if (decompressesTransparently) {
            assertThat(body).isEqualTo(PeerServer.GZIP_PLAINTEXT)
            assertThat(logged).isEqualTo(PeerServer.GZIP_PLAINTEXT)
        } else {
            // The engine handed the compressed bytes through, and the converter decoded them with the
            // declared UTF-8 charset; the tee saw the same bytes and decodes them with the same
            // charset, so the line must show exactly the text the application got - which is not
            // the plaintext and contains none of it.
            assertThat(body).isNotEqualTo(PeerServer.GZIP_PLAINTEXT).doesNotContain("compressed hello")
            assertThat(logged).isEqualTo(body)
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
        // The cause type is engine-internal detail the contract does not promise (CONTRIBUTING.md,
        // test rules): the classification is what is pinned, the engine's exception only that one exists.
        assertThat(event.throwableProxy).isNotNull()
    }

    /** What a call on its own thread ended with: the exception it threw, and its interrupt flag right after. */
    protected class AbandonedCall(
        val thrown: Throwable?,
        val interruptedAfter: Boolean,
    )

    /**
     * A call run on a thread of its own - started on a `Thread.Builder` through [startOn], or submitted
     * to an executor as the `Runnable` it is - that records what it threw and the thread's interrupt
     * flag right after: the scaffolding every interrupt scenario shares. [result] waits for it, bounded
     * by `GENEROUS`.
     */
    protected class ThreadedCall(
        private val call: () -> Any?,
    ) : Runnable {
        private val thrown = AtomicReference<Throwable?>()
        private val interruptedAfter = AtomicBoolean()
        private val done = CountDownLatch(1)

        override fun run() {
            try {
                thrown.set(catchThrowable { call() })
                interruptedAfter.set(Thread.currentThread().isInterrupted)
            } finally {
                done.countDown()
            }
        }

        /** Starts the call on a thread of [threads]; the thread is returned for the scenario to interrupt. */
        fun startOn(threads: Thread.Builder): Thread = threads.start(this)

        /** What the call ended with, once it returned - a wait guard, so a call that never returns fails the test. */
        fun result(): AbandonedCall {
            check(done.await(GENEROUS.toMillis(), TimeUnit.MILLISECONDS)) { "the call never returned" }
            return AbandonedCall(thrown.get(), interruptedAfter.get())
        }
    }

    /** Polls [condition] until it holds - a wait guard, bounded by `GENEROUS`, that fails naming [description]. */
    protected fun awaitUntil(
        description: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + GENEROUS.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting until $description" }
            Thread.sleep(5)
        }
    }

    /** Blocks until the peer has the request - the call is then in flight, waiting for `/slow`'s answer. */
    private fun awaitRequestAtPeer() = awaitUntil("the request reached the peer") { peer.received.isNotEmpty() }

    /** A `/slow` call on a thread of [threads], interrupted once the request reached the peer. */
    private fun interruptedMidCall(threads: Thread.Builder): AbandonedCall {
        val client = client(peer.baseUrl)
        val call =
            ThreadedCall {
                client
                    .get()
                    .uri("/slow")
                    .retrieve()
                    .body(String::class.java)
            }
        val worker = call.startOn(threads)
        awaitRequestAtPeer()
        worker.interrupt()
        return call.result()
    }

    private fun assertCancelled(call: AbandonedCall) {
        // Only THAT the call failed: what an engine throws on an interrupt is engine-internal (CONTRIBUTING.md,
        // test rules) - Apache's classic client even retries the socket the interrupt closed, and the
        // retry's pool lease throws a raw CancellationException past RestClient's wrapper.
        assertThat(call.thrown).describedAs("the call failed").isNotNull()
        assertThat(call.interruptedAfter).describedAs("interrupt flag after the emission").isTrue()
        val event = log.events.single()
        assertThat(event.level).describedAs("event for %s", call.thrown).isEqualTo(Level.WARN)
        assertThat(event.formattedMessage).startsWith("Adapter http exchange GET ${peer.baseUrl}/slow -> - [")
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "cancelled").doesNotContainKey("adapter_response_status_code")
        assertThat(event.throwableProxy).isNull()
    }

    @Test
    fun `should log a caller interrupted on a virtual thread as WARN cancelled through this request factory`() {
        // What is tested: the classification against what this engine really does when the caller's
        //   virtual thread is interrupted mid-call - the JDK closes the socket ("Closed by interrupt"),
        //   or the engine stops waiting on its future and rethrows the interrupt wrapped.
        // Success criteria: the call fails; exactly one event, WARN, cancelled, `-> -`, no status, no
        //   throwable; the thread's interrupt flag is still set after the emission.
        // Why it matters: a caller that stopped waiting is no failure of the peer (ADR-0015), and the
        //   interrupt is the caller's - the interceptor must leave it for the caller's own handling.
        // Given/When
        val call = interruptedMidCall(Thread.ofVirtual())

        // Then
        assertCancelled(call)
    }

    @Test
    fun `should log a caller interrupted on a platform thread the way this request factory honours it`() {
        // What is tested: the same interrupt on a PLATFORM thread, where only an engine that waits on a
        //   future wakes up (`interruptibleOnPlatformThreads`).
        // Success criteria: an interruptible engine logs exactly what the virtual-thread case logs; one
        //   that blocks in a socket read finishes with the peer's answer - INFO success with status 200 -
        //   and the interrupt flag is still set either way.
        // Why it matters: the outcome must report what happened to the exchange - an answer that arrived
        //   is a success, however impatient the caller became meanwhile.
        // Given/When
        val call = interruptedMidCall(Thread.ofPlatform())

        // Then
        if (interruptibleOnPlatformThreads) {
            assertCancelled(call)
        } else {
            assertThat(call.thrown).isNull()
            assertThat(call.interruptedAfter).describedAs("interrupt flag after the emission").isTrue()
            val event = log.events.single()
            assertThat(event.level).isEqualTo(Level.INFO)
            assertThat(keyValues(event)).containsEntry("adapter_outcome", "success").containsEntry("adapter_response_status_code", 200)
        }
    }

    @Test
    fun `should log a worker cancelled through its future as WARN cancelled through this request factory`() {
        // What is tested: the TimeLimiter shape - the call runs on a worker while the caller waits on a
        //   Future; the caller's budget expires and it cancels the future with cancel(true), which
        //   interrupts the worker (a virtual one, so every engine is interruptible).
        // Success criteria: exactly one event, WARN, cancelled, `-> -`, no status, no throwable; the
        //   worker's interrupt flag is still set after the emission.
        // Why it matters: under load this is the most frequent abandoned exchange, and it read as an
        //   ERROR failure before - filling the level alerting fires on with "the caller left".
        // Given
        val client = client(peer.baseUrl)
        val call =
            ThreadedCall {
                client
                    .get()
                    .uri("/slow")
                    .retrieve()
                    .body(String::class.java)
            }
        val result =
            Executors.newVirtualThreadPerTaskExecutor().use { workers ->
                val future = workers.submit(call)
                awaitRequestAtPeer()

                // When: the caller's budget expires
                future.cancel(true)
                call.result()
            }

        // Then
        assertCancelled(result)
    }

    companion object {
        /** For every scenario whose subject is not the timeout: a loaded runner must not turn a tee test into a timeout test. */
        @JvmStatic
        protected val GENEROUS: Duration = Duration.ofSeconds(10)
    }
}
