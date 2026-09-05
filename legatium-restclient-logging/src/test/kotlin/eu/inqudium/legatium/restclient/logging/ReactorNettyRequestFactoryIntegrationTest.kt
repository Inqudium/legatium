package eu.inqudium.legatium.restclient.logging

import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.ReactorClientHttpRequestFactory
import reactor.netty.http.client.HttpClient
import java.time.Duration

/**
 * [RequestFactoryContract] on Reactor Netty, blocking through `ReactorClientHttpRequestFactory`. Its
 * read timeout is `ReadTimeoutException` (an `io.netty.handler.timeout.TimeoutException`), its connect
 * timeout `io.netty.channel.ConnectTimeoutException` - both matched by name, neither a JDK type, and
 * both reach the interceptor inside the factory's `IOException` wrapper. Does NOT decompress: Reactor
 * Netty's `compress(false)` default sends no `Accept-Encoding` and decodes nothing.
 */
class ReactorNettyRequestFactoryIntegrationTest : RequestFactoryContract() {
    override val decompressesTransparently: Boolean = false

    override fun requestFactory(
        connectTimeout: Duration,
        readTimeout: Duration,
    ): ClientHttpRequestFactory {
        val factory = ReactorClientHttpRequestFactory(HttpClient.create())
        factory.setConnectTimeout(connectTimeout)
        factory.setReadTimeout(readTimeout)
        return factory
    }
}
