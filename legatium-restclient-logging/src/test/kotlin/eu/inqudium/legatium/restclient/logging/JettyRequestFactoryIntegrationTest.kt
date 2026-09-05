package eu.inqudium.legatium.restclient.logging

import org.eclipse.jetty.client.HttpClient
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.JettyClientHttpRequestFactory
import java.time.Duration

/**
 * [RequestFactoryContract] on Jetty's client. Its connect timeout is a `SocketTimeoutException`, its
 * read timeout a `java.util.concurrent.TimeoutException` - both JDK types. DECOMPRESSES transparently:
 * the client's default `GZIPContentDecoder` decodes a gzip answer, so application and tee see the
 * plaintext. The Jetty client is a lifecycle: the factory starts it, the test stops it.
 */
class JettyRequestFactoryIntegrationTest : RequestFactoryContract() {
    override val decompressesTransparently: Boolean = true

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
