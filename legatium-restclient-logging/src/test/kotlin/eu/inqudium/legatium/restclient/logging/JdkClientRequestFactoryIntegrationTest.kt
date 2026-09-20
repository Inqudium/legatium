package eu.inqudium.legatium.restclient.logging

import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import java.net.http.HttpClient
import java.time.Duration

/** The bound on releasing the JDK client after a test: generous, since it only matters when the JDK hangs. */
private val ENGINE_RELEASE: Duration = Duration.ofSeconds(30)

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
        val httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build()
        // Released BOUNDED: close() is shutdown() plus an UNBOUNDED awaitTermination(), and this client
        // still owns a cancelled connect (Tarpit) or read (/slow) whose selector-side cleanup must finish
        // first - a JDK whose cleanup hangs would hang the suite until the job timeout instead of failing
        // with a cause. shutdownNow() plus a bounded wait releases what can be released.
        closing(
            AutoCloseable {
                httpClient.shutdownNow()
                httpClient.awaitTermination(ENGINE_RELEASE)
            },
        )
        return JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(readTimeout) }
    }
}
