package eu.inqudium.legatium.restclient.logging

import eu.inqudium.legatium.common.BodyReadState
import eu.inqudium.legatium.common.decodeTruncated
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * A bounded tee target: the interceptor copies the request body it is handed and the response-body tee
 * copies every byte the application reads, up to [maxBytes]; beyond the cap bytes are only counted.
 *
 * The capture is a passive copy - it never buffers, replays, or withholds bytes from the application - so
 * there is no lifecycle to manage and nothing to mark complete: at the moment the exchange line is
 * written (response close), whatever has flowed is what gets logged.
 *
 * Single-writer, single-late-reader concurrency model: the application reads the response body on one
 * thread at a time, and the emission reads once, at response close. On the blocking stack that is the
 * SAME thread in the overwhelming majority of cases; visibility across a handoff to another thread
 * (a response passed to a worker and closed there) is still established by THIS class rather than
 * assumed: [totalBytes] is `@Volatile` and is written LAST in every mutation, so the reader's initial
 * [totalBytes] read publishes all preceding buffer writes (a piggybacked happens-before edge).
 *
 * With `maxBytes = 0` the capture runs in COUNT-ONLY mode: nothing is buffered, [totalBytes] still
 * counts every byte - the mode the body-size metrics use when body logging is off.
 *
 * Besides the bytes, the response capture records HOW FAR the application consumed the body
 * ([readState]): the tee mirrors consumption, not transmission, so a response body the application never
 * read - or stopped reading half-way - is invisible in the byte count alone. The response tee marks the
 * start of consumption and the end of the stream; the emitter turns the state into the
 * `adapter.response.body.read` counter. The end of the stream is observed in two ways: an EOF the
 * application saw, or the byte count reaching the length the response DECLARED ([expectBytes]) - a reader
 * that knows the length asks for exactly that many bytes and never for the EOF (Spring's
 * `ByteArrayHttpMessageConverter` does), and must not be counted as having stopped early.
 */
internal class BoundedBodyCapture(
    private val maxBytes: Int,
) {
    private val buffer = ByteArrayOutputStream()

    /**
     * How far the application consumed the body - see [BodyReadState]. Volatile for the same
     * writer-to-reader handoff as [totalBytes]; it is a separate fact (a zero-byte body can be read to
     * its end), so it has its own field rather than being derived from the count.
     */
    @Volatile
    var readState: BodyReadState = BodyReadState.UNREAD
        private set

    /**
     * Every byte that flowed, including those beyond the capture limit - the size metrics' source.
     * Volatile, and always the LAST write of a mutation: its write publishes the buffer state to the
     * close-time reader (see the class KDoc), and readers must read it FIRST.
     */
    @Volatile
    var totalBytes: Long = 0
        private set

    /** The length the response declared, [UNKNOWN_LENGTH] when none is trustworthy - see [expectBytes]. */
    @Volatile
    private var expectedBytes: Long = UNKNOWN_LENGTH

    fun capture(b: Int) {
        if (buffer.size() < maxBytes) {
            buffer.write(b)
        }
        advance(1)
    }

    fun capture(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val room = maxBytes - buffer.size()
        if (room > 0) {
            buffer.write(bytes, offset, minOf(length, room))
        }
        advance(length)
    }

    /** Counts [length] bytes, completing the read state when the declared length is reached; [totalBytes] is written LAST. */
    private fun advance(length: Int) {
        val total = totalBytes + length
        if (expectedBytes != UNKNOWN_LENGTH && total >= expectedBytes) {
            readState = BodyReadState.COMPLETE
        }
        totalBytes = total
    }

    /**
     * Tells the capture how many body bytes the response DECLARED (`Content-Length`), so a reader that
     * consumes exactly that many without asking for the EOF still counts as complete. The caller passes
     * only a length it can trust: none for a chunked answer, none when a `Content-Encoding` means the
     * engine may hand the application a transformed body of another length. A declared zero completes
     * the moment the stream is opened.
     */
    fun expectBytes(length: Long) {
        expectedBytes = length
    }

    /**
     * The application opened the body stream: from now on the body counts as (at least) partially read -
     * or as complete right away when the response declared a zero-length body, which a length-aware
     * reader consumes without a single read call.
     */
    fun markStarted() {
        if (readState == BodyReadState.UNREAD) {
            readState = if (expectedBytes == 0L) BodyReadState.COMPLETE else BodyReadState.PARTIAL
        }
    }

    /** The application observed the end of the stream: the body was consumed completely. */
    fun markCompleted() {
        readState = BodyReadState.COMPLETE
    }

    /**
     * The captured bytes decoded with [charset], suffixed with a truncation note when the body was larger
     * than the capture limit. Returns `null` for a body of zero bytes, so the log emission can omit the
     * key entirely instead of logging an empty string.
     */
    fun loggedValue(charset: Charset): String? {
        if (totalBytes == 0L) {
            return null
        }
        return if (totalBytes > buffer.size()) {
            "${decodeTruncated(buffer.toByteArray(), charset)}... [truncated, $totalBytes bytes total]"
        } else {
            buffer.toString(charset)
        }
    }

    companion object {
        /** No trustworthy declared length: completion is observed through the EOF only. */
        const val UNKNOWN_LENGTH = -1L
    }
}
