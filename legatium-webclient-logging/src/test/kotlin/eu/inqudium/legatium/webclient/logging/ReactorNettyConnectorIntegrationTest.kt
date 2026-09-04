package eu.inqudium.legatium.webclient.logging

import io.netty.channel.ChannelOption
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import reactor.netty.http.client.HttpClient
import java.time.Duration

/**
 * [ConnectorContract] on Reactor Netty, the starters' default. Its response timeout is
 * `ReadTimeoutException` (an `io.netty.handler.timeout.TimeoutException`), its connect timeout
 * `io.netty.channel.ConnectTimeoutException` - both matched by name, neither a JDK type.
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
}
