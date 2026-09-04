package eu.inqudium.legatium.webclient.logging

import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.async.HttpAsyncClients
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder
import org.apache.hc.core5.util.Timeout
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.http.client.reactive.HttpComponentsClientHttpConnector
import java.time.Duration

/**
 * [ConnectorContract] on Apache HttpComponents 5's async client. Its connect timeout is
 * `org.apache.hc.client5.http.ConnectTimeoutException`, which IS a `java.net.SocketTimeoutException`;
 * its response timeout surfaces as a `SocketTimeoutException` too - both JDK types, no name of their
 * own in the classification.
 */
class HttpComponentsConnectorIntegrationTest : ConnectorContract() {
    override fun connector(
        connectTimeout: Duration,
        responseTimeout: Duration,
    ): ClientHttpConnector {
        val connections =
            PoolingAsyncClientConnectionManagerBuilder
                .create()
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.of(connectTimeout)).build())
                .build()
        val client =
            HttpAsyncClients
                .custom()
                .setConnectionManager(connections)
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.of(responseTimeout)).build())
                .build()
        return closing(HttpComponentsClientHttpConnector(client))
    }
}
