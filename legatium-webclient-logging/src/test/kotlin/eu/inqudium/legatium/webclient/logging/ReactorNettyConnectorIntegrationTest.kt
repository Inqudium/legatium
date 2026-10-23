package eu.inqudium.legatium.webclient.logging

import ch.qos.logback.classic.Level
import eu.inqudium.legatium.common.keyValues
import io.netty.channel.ChannelOption
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [ConnectorContract] on Reactor Netty, the starters' default. Its third timeout has no counterpart in
 * the contract: the POOL ACQUIRE timeout, raised when no pooled connection became free within the
 * pending-acquire timeout - its classification is pinned here.
 *
 * Assumption: the response timeout is Netty's `ReadTimeoutException` (an
 * `io.netty.handler.timeout.TimeoutException`) and the connect timeout
 * `io.netty.channel.ConnectTimeoutException`, both matched by name and neither a JDK type; the pool
 * acquire timeout is reactor-pool's `PoolAcquireTimeoutException` (shaded into reactor-netty-core), a
 * `java.util.concurrent.TimeoutException` (probed on Reactor Netty 1.3.7, Netty 4.2.17.Final).
 */
class ReactorNettyConnectorIntegrationTest : ConnectorContract() {
    override fun connector(
        connectTimeout: Duration,
        responseTimeout: Duration,
    ): ClientHttpConnector =
        ReactorClientHttpConnector(
            HttpClient
                .create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout.toMillis().toInt())
                .responseTimeout(responseTimeout),
        )

    @Test
    fun `should log this connector's pool acquire timeout as WARN timeout`() {
        // What is tested: the classification against the exception Reactor Netty really raises when its
        //   pool has no free connection within the pending-acquire timeout - a third timeout next to
        //   connect and response, and the reason the classification needs no Netty pool name.
        // Success criteria: against a pool of ONE connection held by a call to `/slow` (1.5 s), a second
        //   call with a 200 ms acquire timeout errors, its event is WARN with outcome timeout, `-> -` and
        //   no status, and the peer never saw the second request.
        // Why it matters: an exhausted pool is a timeout to an operator like a connect or response
        //   timeout - the pool in front of the peer is slow, not broken.
        // Given: a pool of one connection with room for one waiter; the first call holds the connection
        //   until `/slow` answers
        val provider =
            ConnectionProvider
                .builder("pool-acquire-timeout")
                .maxConnections(1)
                .pendingAcquireMaxCount(1)
                .pendingAcquireTimeout(SHORT)
                .build()
        closing(AutoCloseable { provider.disposeLater().block(AWAIT) })
        val holding = CountDownLatch(1)
        val httpClient = HttpClient.create(provider).doOnRequest { _, _ -> holding.countDown() }
        val client = webClientBuilder.baseUrl(peer.baseUrl).clientConnector(ReactorClientHttpConnector(httpClient)).build()
        val first =
            client
                .get()
                .uri("/slow")
                .retrieve()
                .bodyToMono(String::class.java)
                .toFuture()
        assertThat(holding.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).describedAs("first call holds the connection").isTrue()

        // When: the second call queues on the pool and times out on acquire long before `/slow` answers
        val thrown =
            catchThrowable {
                client
                    .get()
                    .uri("/things/1")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .block(AWAIT)
            }
        first.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS)

        // Then
        assertThat(thrown).isNotNull()
        assertThat(peer.received.map { it.path }).containsExactly("/slow")
        val event = log.awaitEvents(2).single { it.formattedMessage.contains("/things/1") }
        assertThat(event.level).describedAs("event for %s", thrown).isEqualTo(Level.WARN)
        assertThat(event.formattedMessage).startsWith("Adapter http exchange GET ${peer.baseUrl}/things/1 -> - [")
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "timeout").doesNotContainKey("adapter_response_status_code")
    }
}
