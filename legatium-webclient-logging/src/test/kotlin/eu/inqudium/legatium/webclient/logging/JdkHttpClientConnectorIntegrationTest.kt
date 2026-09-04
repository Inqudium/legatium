package eu.inqudium.legatium.webclient.logging

import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.http.client.reactive.JdkClientHttpConnector
import java.net.http.HttpClient
import java.time.Duration

/**
 * [ConnectorContract] on the JDK's `java.net.http.HttpClient`. Both timeouts are
 * `java.net.http.HttpTimeoutException` (the connect one its `HttpConnectTimeoutException` subtype),
 * matched as JDK types.
 */
class JdkHttpClientConnectorIntegrationTest : ConnectorContract() {
    override fun connector(
        connectTimeout: Duration,
        responseTimeout: Duration,
    ): ClientHttpConnector =
        JdkClientHttpConnector(HttpClient.newBuilder().connectTimeout(connectTimeout).build())
            .apply { setReadTimeout(responseTimeout) }
}
