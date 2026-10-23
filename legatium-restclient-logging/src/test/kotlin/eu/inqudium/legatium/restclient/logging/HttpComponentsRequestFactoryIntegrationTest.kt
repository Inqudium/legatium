package eu.inqudium.legatium.restclient.logging

import ch.qos.logback.classic.Level
import eu.inqudium.legatium.common.keyValues
import org.apache.hc.client5.http.HttpRoute
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.classic.RequestFailedException
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.http.HttpHost
import org.apache.hc.core5.util.TimeValue
import org.apache.hc.core5.util.Timeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.time.Duration
import java.util.concurrent.CancellationException

/**
 * [RequestFactoryContract] on Apache HttpComponents 5's classic client. Assumption: its connect timeout
 * is `org.apache.hc.client5.http.ConnectTimeoutException`, which IS a `java.net.SocketTimeoutException`,
 * and its response timeout surfaces as a `SocketTimeoutException` too - both JDK types, no name of their
 * own in the classification (probed on httpclient5 5.6.4). Its third timeout has no counterpart in the
 * contract: the POOL LEASE timeout, whose classification as WARN `timeout` is pinned here. Assumption:
 * the classic client raises it as `org.apache.hc.core5.http.ConnectionRequestTimeoutException`, an
 * `InterruptedIOException`, matched by name (probed on httpclient5 5.6.4). Pinned here too: a caller
 * interrupted while it waits for that connection, on a platform and on a virtual thread, is `cancelled`
 * (ADR-0015), with the exceptions the cancellation page documents. DECOMPRESSES transparently: the
 * client's content-compression interceptor decodes a gzip answer and strips `Content-Encoding` and
 * `Content-Length`, so application and tee see the plaintext and the capture completes on the EOF.
 */
class HttpComponentsRequestFactoryIntegrationTest : RequestFactoryContract() {
    override val decompressesTransparently: Boolean = true

    override val interruptibleOnPlatformThreads: Boolean = false

    // docs/cancellation-by-engine.md, section "Per engine": the retry's pool lease after the closed socket, passed through RestClient unwrapped
    override val abandonedCallExceptions: AbandonedCallExceptions =
        AbandonedCallExceptions(
            atInterceptor = ExpectedThrowable(CancellationException::class.java),
            toCaller = CancellationException::class.java,
        )

    override fun requestFactory(
        connectTimeout: Duration,
        readTimeout: Duration,
    ): ClientHttpRequestFactory {
        val connections =
            PoolingHttpClientConnectionManagerBuilder
                .create()
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.of(connectTimeout)).build())
                .build()
        return requestFactory(connections).also { it.setReadTimeout(readTimeout) }
    }

    private fun requestFactory(connections: PoolingHttpClientConnectionManager): HttpComponentsClientHttpRequestFactory {
        val factory = HttpComponentsClientHttpRequestFactory(HttpClients.custom().setConnectionManager(connections).build())
        closing(AutoCloseable { factory.destroy() })
        return factory
    }

    /**
     * A client on a pool of ONE connection whose only slot is held for the test - leased directly from
     * the manager, so no second call, no second thread, no second event, and released before the client
     * is destroyed - with [leaseTimeout] as the connection request timeout.
     */
    private fun clientOnExhaustedPool(
        leaseTimeout: Duration,
        recorder: EngineThrowRecorder? = null,
    ): RestClient {
        val connections =
            PoolingHttpClientConnectionManagerBuilder
                .create()
                .setMaxConnTotal(1)
                .setMaxConnPerRoute(1)
                .build()
        val factory = requestFactory(connections).also { it.setConnectionRequestTimeout(leaseTimeout) }
        val held = connections.lease("held", HttpRoute(HttpHost.create(peer.baseUrl)), Timeout.of(GENEROUS), null).get(Timeout.of(GENEROUS))
        closing(AutoCloseable { connections.release(held, null, TimeValue.ZERO_MILLISECONDS) })
        return restClientBuilder
            .baseUrl(peer.baseUrl)
            .requestFactory(factory)
            .also { builder -> recorder?.let { builder.requestInterceptor(it) } }
            .build()
    }

    @Test
    fun `should log this request factory's pool lease timeout as WARN timeout`() {
        // What is tested: the classification against the exception the classic client really raises
        //   when its pool has no free connection within the connection request timeout - an
        //   InterruptedIOException, not a SocketTimeoutException like its other two timeouts - wrapped
        //   in RestClient's ResourceAccessException.
        // Success criteria: against a pool of ONE connection whose only slot is held, with a 200 ms
        //   lease timeout, the call throws ResourceAccessException, the single event is WARN with
        //   outcome timeout, `-> -` and no status, and the peer never saw a request.
        // Why it matters: an exhausted pool is a timeout to an operator like a connect or read timeout
        //   - the pool in front of the peer is slow, not broken - and read as `failure` before.
        // Given: a pool whose one slot is held, with a 200 ms lease timeout
        val client = clientOnExhaustedPool(SHORT)

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
        assertThat(peer.received).isEmpty()
        val event = log.events.single()
        assertThat(event.level).describedAs("event for %s", thrown).isEqualTo(Level.WARN)
        assertThat(event.formattedMessage).startsWith("Adapter http exchange GET ${peer.baseUrl}/things/1 -> - [")
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "timeout").doesNotContainKey("adapter_response_status_code")
    }

    /** The thread kind a lease scenario's caller runs on - the lease wait must cancel on both. */
    internal enum class ThreadKind(
        val threads: () -> Thread.Builder,
    ) {
        PLATFORM({ Thread.ofPlatform() }),
        VIRTUAL({ Thread.ofVirtual() }),
    }

    /**
     * A call on a thread of [kind] against the exhausted pool, interrupted once it parked on the pool's
     * lease wait - TIMED_WAITING for a virtual thread as for a platform one, the lease being a timed wait.
     */
    private fun interruptedLease(
        kind: ThreadKind,
        recorder: EngineThrowRecorder? = null,
    ): AbandonedCall {
        val client = clientOnExhaustedPool(Duration.ofSeconds(30), recorder)
        val call =
            ThreadedCall {
                client
                    .get()
                    .uri("/things/1")
                    .retrieve()
                    .body(String::class.java)
            }
        val worker = call.startOn(kind.threads())
        awaitUntil("the call parked on the pool") { worker.state == Thread.State.TIMED_WAITING }
        worker.interrupt()
        return call.result()
    }

    @ParameterizedTest
    @EnumSource(ThreadKind::class)
    internal fun `should log a caller interrupted while waiting for a pooled connection as WARN cancelled`(kind: ThreadKind) {
        // What is tested: the classic client's interrupted pool LEASE - the caller's thread, a platform
        //   or a virtual one, is interrupted while it waits for the pool's only slot; the client cancels
        //   the lease, restores the flag and throws RequestFailedException("Request aborted").
        // Success criteria: on both thread kinds ResourceAccessException; exactly one event, WARN,
        //   cancelled, `-> -`, no status, no throwable; the peer never saw a request; the flag is still
        //   set after the emission.
        // Why it matters: a lease wait is where an exhausted pool parks its callers - when their budget
        //   expires there, they left, and the peer had no part in it (ADR-0015).
        // Given: the pool's one slot held, a lease timeout far beyond the test, a caller of the given kind
        // When: the caller is interrupted while it waits for the slot
        val result = interruptedLease(kind)

        // Then
        assertThat(result.thrown).isInstanceOf(ResourceAccessException::class.java)
        assertThat(result.interruptedAfter).describedAs("interrupt flag after the emission").isTrue()
        assertThat(peer.received).isEmpty()
        val event = log.events.single()
        assertThat(event.level).describedAs("event for %s", result.thrown).isEqualTo(Level.WARN)
        assertThat(event.formattedMessage).startsWith("Adapter http exchange GET ${peer.baseUrl}/things/1 -> - [")
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "cancelled").doesNotContainKey("adapter_response_status_code")
        assertThat(event.throwableProxy).isNull()
    }

    @ParameterizedTest
    @EnumSource(ThreadKind::class)
    internal fun `should throw what the cancellation page documents when a caller is interrupted waiting for a pooled connection`(kind: ThreadKind) {
        // What is tested: the engine's side of the interrupted pool LEASE, on a platform and on a
        //   virtual thread - the exception the classic client throws out of `execute` and the one
        //   RestClient hands the caller.
        // Success criteria: on both thread kinds RequestFailedException("Request aborted") over the
        //   InterruptedException at the interceptor; ResourceAccessException at the caller.
        // Why it matters: `docs/cancellation-by-engine.md` documents that the lease wait, unlike the
        //   socket read, cancels cleanly on both thread kinds with the usual wrapper; an upgrade that
        //   changes it on either kind must fail here instead of leaving the page wrong.
        // Given: the pool's one slot held, a recorder next to the engine, a caller of the given kind
        val recorder = EngineThrowRecorder()

        // When: the caller is interrupted while it waits for the slot
        val result = interruptedLease(kind, recorder)

        // Then
        ExpectedThrowable(RequestFailedException::class.java, "Request aborted", InterruptedException::class.java)
            .assertMatches(recorder.thrown, "exception at the interceptor")
        assertThat(result.thrown).describedAs("exception thrown to the caller").isExactlyInstanceOf(ResourceAccessException::class.java)
    }
}
