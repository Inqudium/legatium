package eu.inqudium.legatium.restclient.logging

import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import java.net.http.HttpClient
import java.time.Duration

/**
 * [RequestFactoryContract] on the JDK's `java.net.http.HttpClient`. Both timeouts are
 * `java.net.http.HttpTimeoutException` (the connect one its `HttpConnectTimeoutException` subtype),
 * matched as JDK types. DECOMPRESSES transparently: `java.net.http.HttpClient` itself knows no content
 * coding, but Spring's `JdkClientHttpRequestFactory` enables compression support by default (Spring
 * Framework 7, `enableCompression`) and decodes a gzip answer in its response wrapper - which sits
 * BELOW the interceptor, so the tee sees the plaintext too.
 */
class JdkClientRequestFactoryIntegrationTest : RequestFactoryContract() {
    override val decompressesTransparently: Boolean = true

    override fun requestFactory(
        connectTimeout: Duration,
        readTimeout: Duration,
    ): ClientHttpRequestFactory {
        // Released BOUNDED (closingJdkClient): this client still owns a cancelled connect (Tarpit) or
        // read (/slow) whose selector-side cleanup must finish before close() would return.
        val httpClient = closingJdkClient(HttpClient.newBuilder().connectTimeout(connectTimeout).build())
        return JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(readTimeout) }
    }
}
