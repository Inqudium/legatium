package eu.inqudium.legatium.restclient.logging

import org.eclipse.jetty.client.HttpClient
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.JettyClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
import java.io.IOException
import java.time.Duration

/**
 * [RequestFactoryContract] on Jetty's client. Assumption: its connect timeout is a
 * `SocketTimeoutException`, its read timeout a `java.util.concurrent.TimeoutException` - both JDK types
 * (probed on jetty-client 12.1.12). DECOMPRESSES transparently:
 * the client's default `GZIPContentDecoder` decodes a gzip answer, so application and tee see the
 * plaintext. The Jetty client is a lifecycle: the factory starts it, the test stops it.
 */
class JettyRequestFactoryIntegrationTest : RequestFactoryContract() {
    override val decompressesTransparently: Boolean = true

    override val interruptibleOnPlatformThreads: Boolean = true

    // docs/cancellation-by-engine.md, section "Per engine": the interrupt wrapped by Spring's factory, the flag restored
    override val abandonedCallExceptions: AbandonedCallExceptions =
        AbandonedCallExceptions(
            atInterceptor = ExpectedThrowable(IOException::class.java, "Request was interrupted", InterruptedException::class.java),
            toCaller = ResourceAccessException::class.java,
        )

    override fun requestFactory(
        connectTimeout: Duration,
        readTimeout: Duration,
    ): ClientHttpRequestFactory {
        val factory = JettyClientHttpRequestFactory(HttpClient())
        factory.setConnectTimeout(connectTimeout)
        factory.setReadTimeout(readTimeout)
        factory.afterPropertiesSet()
        closing(AutoCloseable { factory.destroy() })
        return factory
    }
}
