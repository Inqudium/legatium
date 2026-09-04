package eu.inqudium.legatium.webclient.logging

import org.eclipse.jetty.client.HttpClient
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.http.client.reactive.JettyClientHttpConnector
import java.time.Duration

/**
 * [ConnectorContract] on Jetty's reactive client. Its connect timeout is a `SocketTimeoutException`,
 * its idle (response) timeout a `java.util.concurrent.TimeoutException` - both JDK types. The Jetty
 * client is a lifecycle: the connector starts it lazily, the test stops it.
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
