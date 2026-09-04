package eu.inqudium.legatium.restclient.logging

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.ClientHttpResponse
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
 * `adapter.logging.exchanges.open` gauge, the module's liveness signal, rather than logging a guess.
 *
 * The tee records the READ STATE on the capture: opening the stream marks consumption as started;
 * observing the end of the stream (an EOF return from either `read`) marks it complete. Both are
 * observations of what the application did, never an extra read: the tee does not probe for EOF itself,
 * so a body the application stopped reading stays PARTIAL. Any exception while OPENING or reading the
 * body (an [IOException] from a dropped connection or a read timeout, an engine's unchecked wrapper) is
 * reported through [onReadFailure] - the exchange then classifies as a failure or timeout although a
 * status was already received - and rethrown unchanged.
 *
 * Single reader: the body is opened and read by one thread at a time, as every client does; the memo of
 * the tee stream is volatile for the documented handoff to a closing thread, not for concurrent readers.
 *
 * [close] runs the delegate's close FIRST (returning the connection to the pool is the client's business
 * and must never wait for a log line) and [onClose] in a `finally`, so a throwing delegate still completes
 * the exchange. [onClose] is the interceptor's exactly-once completion; a double close is harmless.
 */
internal class CapturingClientHttpResponse(
    private val delegate: ClientHttpResponse,
    private val capture: BoundedBodyCapture?,
    private val onReadFailure: (Exception) -> Unit,
    private val onClose: () -> Unit,
) : ClientHttpResponse {
    @Volatile
    private var teeBody: InputStream? = null

    /** Whether a capture is attached - the read-failure reporting wrapper exists either way; exposed for the tests. */
    internal val capturing: Boolean
        get() = capture != null

    override fun getStatusCode(): HttpStatusCode = delegate.statusCode

    override fun getStatusText(): String = delegate.statusText

    override fun getHeaders(): HttpHeaders = delegate.headers

    override fun getBody(): InputStream {
        teeBody?.let { return it }
        // Opening the stream is an engine call that can fail like a read (getBody throws IOException);
        // reported the same way, so a body that could not even be opened is not logged as a success.
        val real = guarded { delegate.body }
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
        }.also { teeBody = it }
    }

    /** Runs an engine call; ANY exception is reported as the read failure of the exchange and rethrown unchanged. */
    private inline fun <T> guarded(read: () -> T): T =
        try {
            read()
        } catch (e: Exception) {
            onReadFailure(e)
            throw e
        }

    override fun close() {
        try {
            delegate.close()
        } finally {
            onClose()
        }
    }
}
