package eu.inqudium.legatium.restclient.logging

import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.util.Timeout
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import java.time.Duration

/**
 * [RequestFactoryContract] on Apache HttpComponents 5's classic client. Its connect timeout is
 * `org.apache.hc.client5.http.ConnectTimeoutException`, which IS a `java.net.SocketTimeoutException`;
 * its response timeout surfaces as a `SocketTimeoutException` too - both JDK types, no name of their own
 * in the classification. DECOMPRESSES transparently: the client's content-compression interceptor
 * decodes a gzip answer and strips `Content-Encoding` and `Content-Length`, so application and tee see
 * the plaintext and the capture completes on the EOF.
 */
class HttpComponentsRequestFactoryIntegrationTest : RequestFactoryContract() {
    override val decompressesTransparently: Boolean = true

    override fun requestFactory(
        connectTimeout: Duration,
        readTimeout: Duration,
    ): ClientHttpRequestFactory {
        val connections =
            PoolingHttpClientConnectionManagerBuilder
                .create()
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.of(connectTimeout)).build())
                .build()
        val factory = HttpComponentsClientHttpRequestFactory(HttpClients.custom().setConnectionManager(connections).build())
        factory.setReadTimeout(readTimeout)
        closing(AutoCloseable { factory.destroy() })
        return factory
    }
}
