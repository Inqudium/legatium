package eu.inqudium.legatium.common

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * The byte-bounded buffer beneath both twins' `BoundedBodyCapture` (ADR-0003): keeps the first [maxBytes]
 * bytes written to it and renders them as the logged body text. NOT thread-safe - each twin guards it by
 * its own concurrency design (volatile single-writer on the blocking stack, lock and freeze on the
 * reactive one), and counting the bytes that flowed, including those beyond the cap, is the twin's
 * business too: the total comes back in for [render].
 *
 * A bare array rather than a `ByteArrayOutputStream`: the blocking twin's tee stream forwards `reset`,
 * so the buffer must be able to CUT BACK ([truncate]), and the array is sized once by the length the
 * peer or the caller declared ([expect]), so a body within its declaration lands in one allocation
 * without growth. Allocated on the first buffered byte - a buffer nothing is written to (count-only
 * mode, a body never read) costs no memory. Beyond the hint the array doubles, never past [maxBytes].
 */
internal class BoundedByteBuffer(
    private val maxBytes: Int,
) {
    init {
        require(maxBytes >= 0) { "maxBytes must not be negative, got: $maxBytes" }
    }

    private var bytes: ByteArray? = null
    private var expected: Long = UNKNOWN_LENGTH

    /** Bytes buffered so far - at most [maxBytes]. */
    var size: Int = 0
        private set

    /** Room left before [maxBytes]: 0 in count-only mode and once the cap is reached. */
    val remaining: Int
        get() = maxBytes - size

    /** The declared length the first allocation is sized by, [UNKNOWN_LENGTH] without one - exposed for the tests. */
    val expectedBytes: Long
        get() = expected

    /**
     * A SIZING hint: the length the peer or the caller declared. Taken only before the first buffered
     * byte; a value that is not positive means unknown, one beyond the cap sizes to the cap. A wrong
     * hint costs allocation, never bytes - the cap and the twin's count are unaffected.
     */
    fun expect(length: Long) {
        if (bytes == null) {
            expected = length
        }
    }

    /** Buffers [b] when there is room. */
    fun write(b: Int) {
        if (size < maxBytes) {
            room(1)[size++] = b.toByte()
        }
    }

    /** Buffers the first `min(length, remaining)` bytes of the range. */
    fun write(
        src: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val n = minOf(length, remaining)
        if (n > 0) {
            System.arraycopy(src, offset, room(n), size, n)
            size += n
        }
    }

    /** Cuts the buffer back to its first [length] bytes - the blocking twin's reset to a mark. */
    fun truncate(length: Int) {
        require(length in 0..size) { "cannot truncate $size buffered bytes to $length" }
        size = length
    }

    /**
     * The array with room for [n] more bytes: sized on first use by the hint, or the write when there
     * is none; doubled from then on, never past [maxBytes].
     */
    private fun room(n: Int): ByteArray {
        val needed = size + n
        val current = bytes
        if (current != null && current.size >= needed) {
            return current
        }
        val hint = if (expected > 0) minOf(expected, maxBytes.toLong()).toInt() else 0
        val grown = ByteArray(minOf(maxBytes, maxOf(needed, hint, (current?.size ?: 0) * 2)))
        if (current != null) {
            System.arraycopy(current, 0, grown, 0, size)
        }
        bytes = grown
        return grown
    }

    /**
     * The buffered bytes decoded with [charset], suffixed with a truncation note when [total] - the
     * bytes that flowed - exceeds what is buffered. Null for a body of zero bytes, so the emission can
     * omit the key instead of logging an empty string.
     */
    fun render(
        charset: Charset,
        total: Long,
    ): String? {
        if (total == 0L) {
            return null
        }
        return if (total > size) {
            renderTruncated(charset, total)
        } else {
            String(bytes ?: ByteArray(0), 0, size, charset)
        }
    }

    /**
     * Decodes the buffered prefix ONCE, through a small scratch buffer, and appends the truncation note
     * to the same `StringBuilder`. The capture limit bounds bytes, not characters, so the cut can fall
     * inside a multi-byte sequence: decoding with `endOfInput = false` leaves an incomplete trailing
     * sequence undecoded instead of rendering it as a replacement character, while malformed or
     * unmappable sequences INSIDE the prefix are replaced, as `String(bytes, charset)` would. The decoder
     * is deliberately not finalized or flushed.
     *
     * The builder reserves one char per buffered byte plus the note. This avoids capacity growth for
     * decoders with `maxCharsPerByte <= 1` (every HTTP charset); larger output is accommodated by the
     * builder's normal growth. Stored compactly, the transient footprint is the buffered bytes plus the
     * builder plus the result, not a char array of twice the buffered bytes plus the result.
     */
    private fun renderTruncated(
        charset: Charset,
        total: Long,
    ): String {
        val note = "... [truncated, $total bytes total]"
        val length = size
        if (length == 0) {
            return note
        }
        val buffered = checkNotNull(bytes)
        val decoder =
            charset
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val input = ByteBuffer.wrap(buffered, 0, length)
        // A UTF-16 surrogate pair requires two output slots.
        var scratch = CharBuffer.allocate(minOf(SCRATCH_CHARS, maxOf(2, length)))
        val output = StringBuilder(Math.addExact(length, note.length))
        while (true) {
            val inputBefore = input.position()
            val result = decoder.decode(input, scratch, false)
            val produced = scratch.position()
            // With both actions set to REPLACE, an error violates the contract.
            check(!result.isError) { "Decoder returned $result despite REPLACE" }
            output.append(scratch.array(), scratch.arrayOffset(), produced)
            if (result.isUnderflow) {
                break
            }
            // OVERFLOW without progress: the empty output buffer cannot accommodate the next decoding
            // step (no JDK decoder needs more than a surrogate pair, but the contract allows it).
            // Retry with more space.
            if (input.position() == inputBefore && produced == 0) {
                scratch = CharBuffer.allocate(Math.multiplyExact(scratch.capacity(), 2))
            } else {
                scratch.clear()
            }
        }
        return output.append(note).toString()
    }

    companion object {
        /** No trustworthy declared length. */
        const val UNKNOWN_LENGTH = -1L

        /** The scratch `CharBuffer` [renderTruncated] decodes through - 2 KiB, whatever the cap. Exposed for the tests. */
        internal const val SCRATCH_CHARS = 1024
    }
}
