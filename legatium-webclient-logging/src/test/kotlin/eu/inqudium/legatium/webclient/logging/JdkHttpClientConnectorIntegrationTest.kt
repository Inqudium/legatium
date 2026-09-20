package eu.inqudium.legatium.webclient.logging

import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.http.client.reactive.JdkClientHttpConnector
import java.net.http.HttpClient
import java.time.Duration

/** The bound on releasing the JDK client after a test: generous, since it only matters when the JDK hangs. */
private val ENGINE_RELEASE: Duration = Duration.ofSeconds(30)

/**
 * [ConnectorContract] on the JDK's `java.net.http.HttpClient`. Both timeouts are
 * `java.net.http.HttpTimeoutException` (the connect one its `HttpConnectTimeoutException` subtype),
 * matched as JDK types.
 */
class JdkHttpClientConnectorIntegrationTest : ConnectorContract() {
    override fun connector(
        connectTimeout: Duration,
        responseTimeout: Duration,
    ): ClientHttpConnector {
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
        return JdkClientHttpConnector(httpClient).apply { setReadTimeout(responseTimeout) }
    }
}
