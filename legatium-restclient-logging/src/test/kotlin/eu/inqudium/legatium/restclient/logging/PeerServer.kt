package eu.inqudium.legatium.restclient.logging

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The foreign party the integration tests send their envoy to: the JDK's own HTTP server on an
 * ephemeral port - no container, no extra dependency - with a handful of routes and a record of every
 * request it received (method, path, headers, body), so a test can assert what actually went over the
 * wire.
 */
internal class PeerServer : AutoCloseable {
    class Received(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun header(name: String): String? =
            headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.firstOrNull()
    }

    val received = CopyOnWriteArrayList<Received>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val baseUrl: String
        get() = "http://127.0.0.1:${server.address.port}"

    val host: String
        get() = "127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
        received.add(Received(exchange.requestMethod, exchange.requestURI.rawPath, exchange.requestHeaders.toMap(), body))
        val path = exchange.requestURI.path
        when {
            path.startsWith("/things/") -> {
                val id = path.removePrefix("/things/")
                respond(exchange, 200, "application/json", """{"id":$id,"echo":"$body"}""")
            }

            path == "/fail" -> {
                respond(exchange, 500, "text/plain", "boom")
            }

            path == "/slow" -> {
                Thread.sleep(1_500)
                respond(exchange, 200, "text/plain", "late")
            }

            path == "/empty" -> {
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }

            else -> {
                respond(exchange, 404, "text/plain", "no such route")
            }
        }
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() = server.stop(0)
}
