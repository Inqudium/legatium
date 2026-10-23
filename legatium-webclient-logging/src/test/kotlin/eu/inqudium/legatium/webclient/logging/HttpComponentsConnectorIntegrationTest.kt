package eu.inqudium.legatium.webclient.logging

import ch.qos.logback.classic.Level
import eu.inqudium.legatium.common.keyValues
import org.apache.hc.client5.http.HttpRoute
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.async.HttpAsyncClients
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder
import org.apache.hc.core5.http.HttpHost
import org.apache.hc.core5.util.TimeValue
import org.apache.hc.core5.util.Timeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.http.client.reactive.HttpComponentsClientHttpConnector
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * [ConnectorContract] on Apache HttpComponents 5's async client. Its third timeout has no counterpart
 * in the contract: the POOL LEASE timeout, raised when no pooled connection became free within the
 * connection request timeout - its classification is pinned here.
 *
 * Assumption: the connect timeout is `org.apache.hc.client5.http.ConnectTimeoutException`, which IS a
 * `java.net.SocketTimeoutException`, and the response timeout surfaces as a `SocketTimeoutException`
 * too - both JDK types, no name of their own in the classification; the async client fails a lease
 * with the pool's `DeadlineTimeoutException`, a `java.util.concurrent.TimeoutException` (the classic
 * client raises `org.apache.hc.core5.http.ConnectionRequestTimeoutException` instead, matched by name)
 * (probed on HttpClient 5.6.4, HttpCore 5.4.3).
 */
class HttpComponentsConnectorIntegrationTest : ConnectorContract() {
    override fun connector(
        connectTimeout: Duration,
        responseTimeout: Duration,
    ): ClientHttpConnector {
        val connections =
            PoolingAsyncClientConnectionManagerBuilder
                .create()
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.of(connectTimeout)).build())
                .build()
        return connector(connections, RequestConfig.custom().setResponseTimeout(Timeout.of(responseTimeout)).build())
    }

    private fun connector(
        connections: PoolingAsyncClientConnectionManager,
        requestConfig: RequestConfig,
    ): ClientHttpConnector {
        val client =
            HttpAsyncClients
                .custom()
                .setConnectionManager(connections)
                .setDefaultRequestConfig(requestConfig)
                .build()
        return closing(HttpComponentsClientHttpConnector(client))
    }

    @Test
    fun `should log this connector's pool lease timeout as WARN timeout`() {
        // What is tested: the classification against the exception the async client really raises when
        //   its pool has no free connection within the connection request timeout - a third timeout
        //   next to connect and response. Assumption: the async pool enforces a lease deadline at its
        //   NEXT event (a release, another lease), not on a timer - a connection that comes back after
        //   the deadline fails the waiting request instead of serving it (probed on HttpCore 5.4.3).
        // Success criteria: against a pool of ONE connection whose only slot is held past the 200 ms
        //   lease timeout and then released, the call errors, the single event is WARN with outcome
        //   timeout, `-> -` and no status, and the peer never saw a request.
        // Why it matters: an exhausted pool is a timeout to an operator like a connect or response
        //   timeout - the pool in front of the peer is slow, not broken.
        // Given: the pool's one slot leased directly from the manager - no second call, no second
        //   thread, no second event
        val connections =
            PoolingAsyncClientConnectionManagerBuilder
                .create()
                .setMaxConnTotal(1)
                .setMaxConnPerRoute(1)
                .build()
        val connector = connector(connections, RequestConfig.custom().setConnectionRequestTimeout(Timeout.of(SHORT)).build())
        val held = connections.lease("held", HttpRoute(HttpHost.create(peer.baseUrl)), null, Timeout.ofSeconds(10), null).get(10, TimeUnit.SECONDS)
        val client = webClientBuilder.baseUrl(peer.baseUrl).clientConnector(connector).build()

        // When: the call queues on the pool; the slot comes back only after its deadline has passed
        val call =
            client
                .get()
                .uri("/things/1")
                .retrieve()
                .bodyToMono(String::class.java)
                .toFuture()
        Thread.sleep(SHORT.multipliedBy(2))
        connections.release(held, null, TimeValue.ZERO_MILLISECONDS)
        val thrown = catchThrowable { call.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS) }

        // Then
        assertThat(thrown).isNotNull()
        assertThat(peer.received).isEmpty()
        val event = log.awaitEvents(1).single()
        assertThat(event.level).describedAs("event for %s", thrown).isEqualTo(Level.WARN)
        assertThat(event.formattedMessage).startsWith("Adapter http exchange GET ${peer.baseUrl}/things/1 -> - [")
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "timeout").doesNotContainKey("adapter_response_status_code")
    }
}
