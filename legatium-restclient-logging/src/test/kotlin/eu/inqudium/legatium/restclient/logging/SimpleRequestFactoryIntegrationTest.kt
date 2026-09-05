package eu.inqudium.legatium.restclient.logging

import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import java.time.Duration

/**
 * [RequestFactoryContract] on `HttpURLConnection` (`SimpleClientHttpRequestFactory`), the engine of a
 * `RestTemplate()` constructed by hand. Both timeouts are `java.net.SocketTimeoutException`, matched as
 * a JDK type. Does NOT decompress: the URL connection hands a gzip answer through as sent.
 */
class SimpleRequestFactoryIntegrationTest : RequestFactoryContract() {
    override val decompressesTransparently: Boolean = false

    override fun requestFactory(
        connectTimeout: Duration,
        readTimeout: Duration,
    ): ClientHttpRequestFactory =
        SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(connectTimeout)
            setReadTimeout(readTimeout)
        }
}
