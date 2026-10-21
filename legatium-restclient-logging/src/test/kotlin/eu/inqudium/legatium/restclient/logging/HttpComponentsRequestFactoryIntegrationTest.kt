package eu.inqudium.legatium.restclient.logging

import ch.qos.logback.classic.Level
import eu.inqudium.legatium.common.keyValues
import org.apache.hc.client5.http.HttpRoute
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.http.HttpHost
import org.apache.hc.core5.util.TimeValue
import org.apache.hc.core5.util.Timeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * [RequestFactoryContract] on Apache HttpComponents 5's classic client. Its connect timeout is
 * `org.apache.hc.client5.http.ConnectTimeoutException`, which IS a `java.net.SocketTimeoutException`;
 * its response timeout surfaces as a `SocketTimeoutException` too - both JDK types, no name of their own
 * in the classification. Its third timeout has no counterpart in the contract: the POOL LEASE timeout
 * (`org.apache.hc.core5.http.ConnectionRequestTimeoutException`, an `InterruptedIOException` the classic
 * client raises when no pooled connection became free in time), matched by name and pinned here - as is
 * a caller interrupted while it waits for that connection, which is `cancelled` (ADR-0015).
 * DECOMPRESSES transparently: the client's content-compression interceptor decodes a gzip answer and
 * strips `Content-Encoding` and `Content-Length`, so application and tee see the plaintext and the
 * capture completes on the EOF.
 */
class HttpComponentsRequestFactoryIntegrationTest : RequestFactoryContract() {
    override val decompressesTransparently: Boolean = true

    override val interruptibleOnPlatformThreads: Boolean = false

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
        // Given: the pool's one slot leased directly from the manager - no second call, no second
        //   thread, no second event - and released before the client is destroyed
        val connections =
            PoolingHttpClientConnectionManagerBuilder
                .create()
                .setMaxConnTotal(1)
                .setMaxConnPerRoute(1)
                .build()
        val factory = requestFactory(connections).also { it.setConnectionRequestTimeout(SHORT) }
        val held = connections.lease("held", HttpRoute(HttpHost.create(peer.baseUrl)), Timeout.ofSeconds(10), null).get(Timeout.ofSeconds(10))
        closing(AutoCloseable { connections.release(held, null, TimeValue.ZERO_MILLISECONDS) })
        val client = restClientBuilder.baseUrl(peer.baseUrl).requestFactory(factory).build()

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

    @Test
    fun `should log a caller interrupted while waiting for a pooled connection as WARN cancelled`() {
        // What is tested: the classic client's interrupted pool LEASE - the caller's thread is
        //   interrupted while it waits for the pool's only slot; the client cancels the lease, restores
        //   the flag and throws RequestFailedException("Request aborted").
        // Success criteria: ResourceAccessException; exactly one event, WARN, cancelled, `-> -`, no
        //   status, no throwable; the peer never saw a request; the flag is still set after the emission.
        // Why it matters: a lease wait is where an exhausted pool parks its callers - when their budget
        //   expires there, they left, and the peer had no part in it (ADR-0015).
        // Given: the pool's one slot held, a lease timeout far beyond the test
        val connections =
            PoolingHttpClientConnectionManagerBuilder
                .create()
                .setMaxConnTotal(1)
                .setMaxConnPerRoute(1)
                .build()
        val factory = requestFactory(connections).also { it.setConnectionRequestTimeout(Duration.ofSeconds(30)) }
        val held = connections.lease("held", HttpRoute(HttpHost.create(peer.baseUrl)), Timeout.ofSeconds(10), null).get(Timeout.ofSeconds(10))
        closing(AutoCloseable { connections.release(held, null, TimeValue.ZERO_MILLISECONDS) })
        val client = restClientBuilder.baseUrl(peer.baseUrl).requestFactory(factory).build()
        val thrown = AtomicReference<Throwable?>()
        val interruptedAfter = AtomicBoolean()
        val worker =
            Thread.ofPlatform().start {
                thrown.set(
                    catchThrowable {
                        client
                            .get()
                            .uri("/things/1")
                            .retrieve()
                            .body(String::class.java)
                    },
                )
                interruptedAfter.set(Thread.currentThread().isInterrupted)
            }
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (worker.state != Thread.State.TIMED_WAITING) {
            check(System.nanoTime() < deadline) { "the call never parked on the pool" }
            Thread.sleep(5)
        }

        // When
        worker.interrupt()
        assertThat(worker.join(Duration.ofSeconds(10))).describedAs("the call returned").isTrue()

        // Then
        assertThat(thrown.get()).isInstanceOf(ResourceAccessException::class.java)
        assertThat(interruptedAfter.get()).describedAs("interrupt flag after the emission").isTrue()
        assertThat(peer.received).isEmpty()
        val event = log.events.single()
        assertThat(event.level).describedAs("event for %s", thrown.get()).isEqualTo(Level.WARN)
        assertThat(event.formattedMessage).startsWith("Adapter http exchange GET ${peer.baseUrl}/things/1 -> - [")
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "cancelled").doesNotContainKey("adapter_response_status_code")
        assertThat(event.throwableProxy).isNull()
    }
}
