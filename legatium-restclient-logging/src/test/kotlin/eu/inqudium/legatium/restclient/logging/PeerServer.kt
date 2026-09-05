package eu.inqudium.legatium.restclient.logging

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

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

    // Handlers run on a pool, NOT on the server's single dispatcher thread: the /slow route sleeps on
    // after a client gave up at its timeout, and on the dispatcher thread that sleep would block the
    // accept loop for the next test's request.
    private val handlers: ExecutorService = Executors.newCachedThreadPool()

    init {
        server.executor = handlers
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

            // Gzip regardless of Accept-Encoding, with the COMPRESSED length declared: what the
            // application and the tee then see depends on whether the engine decompresses transparently.
            path == "/gzip" -> {
                val compressed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(GZIP_PLAINTEXT.toByteArray(StandardCharsets.UTF_8)) } }.toByteArray()
                exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
                exchange.responseHeaders.set("Content-Encoding", "gzip")
                exchange.sendResponseHeaders(200, compressed.size.toLong())
                exchange.responseBody.use { it.write(compressed) }
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

    override fun close() {
        server.stop(0)
        handlers.shutdownNow()
    }

    companion object {
        /** The plaintext behind the `/gzip` route - long enough that its gzip form contains no plaintext substring. */
        const val GZIP_PLAINTEXT = "compressed hello from the peer, repeated so the deflate stream has something to compress, compressed hello"
    }
}
