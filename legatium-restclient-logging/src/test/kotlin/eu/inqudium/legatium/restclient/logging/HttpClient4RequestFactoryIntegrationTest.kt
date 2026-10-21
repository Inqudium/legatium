package eu.inqudium.legatium.restclient.logging

import ch.qos.logback.classic.Level
import eu.inqudium.legatium.common.keyValues
import org.apache.http.HttpHost
import org.apache.http.client.config.RequestConfig
import org.apache.http.conn.routing.HttpRoute
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * [RequestFactoryContract] on Apache HttpClient 4 - which Spring Framework 7 ships no request factory
 * for - through the test-scoped [HttpClient4RequestFactory], the adapter a host still on HttpClient 4
 * writes itself. What is pinned is how legatium classifies what HttpClient 4 raises: its connect
 * timeout is `org.apache.http.conn.ConnectTimeoutException` (an `InterruptedIOException`) over the JDK's
 * `SocketTimeoutException`, its read timeout the bare `SocketTimeoutException`. It blocks in the socket
 * read like HttpClient 5, so an interrupt reaches it on a virtual thread only. DECOMPRESSES
 * transparently: the default client's `ResponseContentEncoding` decodes a gzip answer and removes
 * `Content-Encoding` and `Content-Length`.
 */
class HttpClient4RequestFactoryIntegrationTest : RequestFactoryContract() {
    override val decompressesTransparently: Boolean = true

    override val interruptibleOnPlatformThreads: Boolean = false

    override fun requestFactory(
        connectTimeout: Duration,
        readTimeout: Duration,
    ): ClientHttpRequestFactory =
        requestFactory(
            PoolingHttpClientConnectionManager(),
            RequestConfig
                .custom()
                .setConnectTimeout(connectTimeout.toMillis().toInt())
                .setSocketTimeout(readTimeout.toMillis().toInt())
                .build(),
        )

    private fun requestFactory(
        connections: PoolingHttpClientConnectionManager,
        config: RequestConfig,
    ): HttpClient4RequestFactory =
        closing(
            HttpClient4RequestFactory(
                HttpClients
                    .custom()
                    .setConnectionManager(connections)
                    .setDefaultRequestConfig(config)
                    .build(),
            ),
        )

    /**
     * A client on a pool of ONE connection whose only slot is held for the test - leased directly from
     * the manager, so no second call, no second thread, no second event - with [leaseTimeout] as the
     * connection request timeout.
     */
    private fun clientOnExhaustedPool(leaseTimeout: Duration): RestClient {
        val connections = PoolingHttpClientConnectionManager().apply { maxTotal = 1 }
        connections.defaultMaxPerRoute = 1
        val factory = requestFactory(connections, RequestConfig.custom().setConnectionRequestTimeout(leaseTimeout.toMillis().toInt()).build())
        val target = URI.create(peer.baseUrl)
        val held = connections.requestConnection(HttpRoute(HttpHost(target.host, target.port)), null).get(10, TimeUnit.SECONDS)
        closing(AutoCloseable { connections.releaseConnection(held, null, 0, TimeUnit.MILLISECONDS) })
        return restClientBuilder.baseUrl(peer.baseUrl).requestFactory(factory).build()
    }

    @Test
    fun `should log this request factory's pool lease timeout as WARN timeout`() {
        // What is tested: the classification against the exception HttpClient 4 really raises when its
        //   pool has no free connection within the connection request timeout.
        // Success criteria: ResourceAccessException; the single event is WARN with outcome timeout,
        //   `-> -` and no status; the peer never saw a request.
        // Why it matters: an exhausted pool is a timeout to an operator, as it is for HttpClient 5.
        // Given
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

    @Test
    fun `should log a caller interrupted while waiting for a pooled connection as WARN cancelled`() {
        // What is tested: HttpClient 4's interrupted pool LEASE - the caller's thread is interrupted
        //   while it waits for the pool's only slot.
        // Success criteria: ResourceAccessException; exactly one event, WARN, cancelled, `-> -`, no
        //   status, no throwable; the peer never saw a request; the flag is still set after the emission.
        // Why it matters: the lease wait is where an exhausted pool parks its callers; when their budget
        //   expires there, they left (ADR-0015).
        // Given: a lease timeout far beyond the test
        val client = clientOnExhaustedPool(Duration.ofSeconds(30))
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
