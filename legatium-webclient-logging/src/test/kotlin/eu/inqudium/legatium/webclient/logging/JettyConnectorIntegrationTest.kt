package eu.inqudium.legatium.webclient.logging

import org.eclipse.jetty.client.HttpClient
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.http.client.reactive.JettyClientHttpConnector
import java.time.Duration

/**
 * [ConnectorContract] on Jetty's reactive client. The Jetty client is a lifecycle: the connector starts
 * it lazily, the test stops it.
 *
 * Assumption: the connect timeout is a `SocketTimeoutException`, the idle (response) timeout a
 * `java.util.concurrent.TimeoutException` - both JDK types (probed on Jetty 12.1.12).
 */
class JettyConnectorIntegrationTest : ConnectorContract() {
    override fun connector(
        connectTimeout: Duration,
        responseTimeout: Duration,
    ): ClientHttpConnector {
        val httpClient =
            HttpClient().apply {
                this.connectTimeout = connectTimeout.toMillis()
                idleTimeout = responseTimeout.toMillis()
            }
        closing(AutoCloseable { httpClient.stop() })
        return JettyClientHttpConnector(httpClient)
    }
}
