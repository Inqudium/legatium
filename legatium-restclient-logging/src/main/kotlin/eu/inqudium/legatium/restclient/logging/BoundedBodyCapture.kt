package eu.inqudium.legatium.restclient.logging

import eu.inqudium.legatium.common.BodyReadState
import eu.inqudium.legatium.common.BoundedByteBuffer
import java.nio.charset.Charset

/**
 * A bounded tee target: the interceptor copies the request body it is handed and the response-body tee
 * copies every byte the application reads, up to [maxBytes]; beyond the cap bytes are only counted.
 *
 * The capture is a passive copy - it never buffers, replays, or withholds bytes from the application - so
 * there is no lifecycle of its own to manage: at the moment the exchange line is written (response
 * close), whatever has flowed is what gets logged. The only marks it takes are those of the READ STATE
 * below, and they are observations, never actions on the stream.
 *
 * Single-writer, single-late-reader concurrency model: the application reads the response body on one
 * thread at a time, and the emission reads once, at response close. On the blocking stack that is the
 * SAME thread in the overwhelming majority of cases; visibility across a handoff to another thread
 * (a response passed to a worker and closed there) is still established by THIS class rather than
 * assumed. Invariant: [totalBytes] is `@Volatile`, written LAST in every mutation and read FIRST, so the
 * reader's initial [totalBytes] read publishes all preceding buffer writes (a piggybacked happens-before
 * edge).
 *
 * With `maxBytes = 0` the capture runs in COUNT-ONLY mode: nothing is buffered, [totalBytes] still
 * counts every byte - the mode the body-size metrics use when body logging is off; a negative limit is
 * rejected at construction.
 *
 * The capture mirrors the application's READ POSITION, so it follows a `mark`/`reset` of the tee
 * stream: [mark] remembers the count, the buffered length and the read state, [reset] restores them,
 * and the bytes the application then reads again are neither counted nor buffered twice. The bytes
 * live in the shared [BoundedByteBuffer] (cut back on a reset, sized by the declared length); this
 * class adds the count, the read state and the blocking stack's concurrency model.
 *
 * Besides the bytes, the response capture records HOW FAR the application consumed the body
 * ([readState], the `adapter.response.body.read` counter's source): the tee mirrors consumption, not
 * transmission, so a body the application never read - or stopped reading half-way - is invisible in
 * the byte count alone. The tee marks the start of consumption and the end of the stream; the end is
 * observed as an EOF the application saw OR as the byte count reaching the length the response carries
 * ([expectBytes]) - why both, and why a bodiless answer counts complete, is [CapturingClientHttpResponse]'s
 * and [BodyReadState]'s documentation.
 */
internal class BoundedBodyCapture(
    maxBytes: Int,
) {
    private val buffer = BoundedByteBuffer(maxBytes)

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
     * Invariant: volatile, the LAST write of every mutation and the reader's FIRST read - its write
     * publishes the buffer state to the close-time reader (the handoff model of the class KDoc).
     */
    @Volatile
    var totalBytes: Long = 0
        private set

    /** The length the response declared, [UNKNOWN_LENGTH] when none is trustworthy - see [expectBytes]. */
    @Volatile
    private var expectedBytes: Long = UNKNOWN_LENGTH

    // The read position `reset` rewinds to - the start of the stream until `mark` moves it.
    private var markedTotal: Long = 0
    private var markedBuffered = 0
    private var markedState: BodyReadState = BodyReadState.UNREAD

    fun capture(b: Int) {
        buffer.write(b)
        advance(1)
    }

    fun capture(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        buffer.write(bytes, offset, length)
        advance(length)
    }

    /**
     * Counts [length] bytes, completing the read state when the declared length is reached;
     * [totalBytes] is written LAST.
     */
    private fun advance(length: Int) {
        val total = totalBytes + length
        if (expectedBytes != UNKNOWN_LENGTH && total >= expectedBytes) {
            readState = BodyReadState.COMPLETE
        }
        totalBytes = total
    }

    /**
     * Tells the capture how many body bytes the response CARRIES, so a reader that consumes exactly
     * that many without asking for the EOF still counts as complete (the second completion rule of
     * [CapturingClientHttpResponse]) - and sizes the buffer by it. Only a length the caller trusts -
     * the interceptor decides which ([UNKNOWN_LENGTH] otherwise). ZERO completes the read state right
     * here: nothing to read, and the clients never open such a body, so no later mark could
     * ([BodyReadState.COMPLETE]).
     */
    fun expectBytes(length: Long) {
        expectedBytes = length
        buffer.expect(length)
        if (length == 0L) {
            readState = BodyReadState.COMPLETE
        }
    }

    /** The application opened the body stream: from now on the body counts as (at least) partially read. */
    fun markStarted() {
        if (readState == BodyReadState.UNREAD) {
            readState = BodyReadState.PARTIAL
        }
        // The default mark is the start of the stream in the state the open left behind: a reset without
        // a mark rewinds a mark-capable engine stream to its beginning.
        markedState = readState
    }

    /** Remembers the read position for [reset] - the tee's `mark`, taken when the engine stream took its own. */
    fun mark() {
        markedTotal = totalBytes
        markedBuffered = buffer.size
        markedState = readState
    }

    /**
     * Rewinds the count, the buffer and the read state to the last [mark] (or to the start of the
     * stream): the engine stream rewound, so the bytes the application reads next are a REPLAY and must
     * not count twice. [totalBytes] is written LAST, like every mutation.
     */
    fun reset() {
        buffer.truncate(markedBuffered)
        readState = markedState
        totalBytes = markedTotal
    }

    /** The application observed the end of the stream: the body was consumed completely. */
    fun markCompleted() {
        readState = BodyReadState.COMPLETE
    }

    /**
     * The captured bytes decoded with [charset], suffixed with a truncation note when the body was larger
     * than the capture limit. Returns `null` for a body of zero bytes, so the log emission can omit the
     * key entirely instead of logging an empty string. Reads [totalBytes] FIRST (the handoff model).
     */
    fun loggedValue(charset: Charset): String? = buffer.render(charset, totalBytes)

    companion object {
        /** No trustworthy declared length: completion is observed through the EOF only. */
        const val UNKNOWN_LENGTH = BoundedByteBuffer.UNKNOWN_LENGTH
    }
}
