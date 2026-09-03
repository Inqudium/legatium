package eu.inqudium.legatium.restclient.logging

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.ClientHttpResponse
import java.io.IOException
import java.io.InputStream

/**
 * The response handed back to the client: delegates everything to the real [delegate], observes the body
 * the APPLICATION reads - teeing it into [capture] when one exists (a passive copy, never a pre-read),
 * reporting a read failure always - and turns [close] into the EMISSION POINT of the exchange.
 *
 * `RestClient` and `RestTemplate` consume the body AFTER the interceptor chain returned - through the
 * message converters, on the caller's thread - and close the response in a `finally` when they are done
 * (`RestClient.exchange(..., close = true)` and `RestTemplate.doExecute` alike). So the moment the
 * response is closed is the moment the exchange is truly over: status, headers and the body that was
 * actually read are FINAL there. Emitting when the interceptor returns would log a body of zero bytes and
 * a duration that excludes the read - the exact wart the sibling project limesium eliminated on the
 * inbound side by emitting at request destruction. A response the application never closes (a raw
 * `exchange(..., close = false)` the caller forgets to close) never completes - and stays open on the
 * `client.logging.exchanges.open` gauge, the module's liveness signal, rather than logging a guess.
 *
 * The tee records the READ STATE on the capture: opening the stream marks consumption as started;
 * observing the end of the stream (an EOF return from either `read`) marks it complete. Both are
 * observations of what the application did, never an extra read: the tee does not probe for EOF itself,
 * so a body the application stopped reading stays PARTIAL. An [IOException] while reading (the connection
 * dropped mid-body, a read timeout) is reported through [onReadFailure] - the exchange then classifies as
 * a failure or timeout although a status was already received - and rethrown unchanged.
 *
 * [close] runs the delegate's close FIRST (returning the connection to the pool is the client's business
 * and must never wait for a log line) and [onClose] in a `finally`, so a throwing delegate still completes
 * the exchange. [onClose] is the interceptor's exactly-once completion; a double close is harmless.
 */
internal class CapturingClientHttpResponse(
    private val delegate: ClientHttpResponse,
    private val capture: BoundedBodyCapture?,
    private val onReadFailure: (IOException) -> Unit,
    private val onClose: () -> Unit,
) : ClientHttpResponse {
    private var teeBody: InputStream? = null

    override fun getStatusCode(): HttpStatusCode = delegate.statusCode

    override fun getStatusText(): String = delegate.statusText

    override fun getHeaders(): HttpHeaders = delegate.headers

    override fun getBody(): InputStream {
        teeBody?.let { return it }
        val real = delegate.body
        capture?.markStarted()
        return object : InputStream() {
            override fun read(): Int {
                val b = guarded { real.read() }
                if (b != -1) {
                    capture?.capture(b)
                } else {
                    capture?.markCompleted()
                }
                return b
            }

            override fun read(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                val n = guarded { real.read(bytes, offset, length) }
                if (n > 0) {
                    capture?.capture(bytes, offset, n)
                } else if (n == -1) {
                    capture?.markCompleted()
                }
                return n
            }

            override fun available(): Int = real.available()

            override fun close() = real.close()

            private inline fun guarded(read: () -> Int): Int =
                try {
                    read()
                } catch (e: IOException) {
                    onReadFailure(e)
                    throw e
                }
        }.also { teeBody = it }
    }

    override fun close() {
        try {
            delegate.close()
        } finally {
            onClose()
        }
    }
}
