package eu.inqudium.legatium.restclient.logging

import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.ReactorClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
import reactor.netty.http.client.HttpClient
import java.io.IOException
import java.time.Duration

/**
 * [RequestFactoryContract] on Reactor Netty, blocking through `ReactorClientHttpRequestFactory`.
 * Assumption: its read timeout is `ReadTimeoutException` (an `io.netty.handler.timeout.TimeoutException`),
 * its connect timeout `io.netty.channel.ConnectTimeoutException` - both matched by name, neither a JDK
 * type, and both reach the interceptor inside the factory's `IOException` wrapper (probed on Reactor
 * Netty 1.3.7, Spring Framework 7.0.9). Does NOT decompress: Reactor
 * Netty's `compress(false)` default sends no `Accept-Encoding` and decodes nothing.
 */
class ReactorNettyRequestFactoryIntegrationTest : RequestFactoryContract() {
    override val decompressesTransparently: Boolean = false

    override val interruptibleOnPlatformThreads: Boolean = true

    // docs/cancellation-by-engine.md, section "Per engine": the interrupt of `block()` wrapped in an IOException
    override val abandonedCallExceptions: AbandonedCallExceptions =
        AbandonedCallExceptions(
            atInterceptor = ExpectedThrowable(IOException::class.java),
            toCaller = ResourceAccessException::class.java,
        )

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
