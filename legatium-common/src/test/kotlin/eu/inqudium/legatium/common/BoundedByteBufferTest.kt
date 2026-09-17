package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CharsetEncoder
import java.nio.charset.CoderResult
import java.nio.charset.StandardCharsets

/**
 * The shared buffer beneath both twins' captures: the cap, the growth and sizing hint, the cut-back for
 * a reset, and the rendering. The twins' own tests drive it through their captures (count, read state,
 * concurrency, the truncation at a character boundary); this test owns the buffer's contract alone.
 */
class BoundedByteBufferTest {
    private fun bytes(text: String) = text.toByteArray(StandardCharsets.UTF_8)

    private fun BoundedByteBuffer.write(text: String) = bytes(text).let { write(it, 0, it.size) }

    @Nested
    inner class `Cap and growth` {
        @Test
        fun `should keep the first bytes up to the cap through both write paths`() {
            // What is tested: writes in chunks and single bytes against a 16-byte cap, the last chunk
            //   clipped at the cap.
            // Success criteria: size and remaining follow the writes exactly; rendering 18 flowed
            //   bytes shows the first 16 in order with the note.
            // Why it matters: the bytes the twins log come out of this array; a write path that
            //   dropped or reordered a byte would corrupt every logged body.
            // Given
            val buffer = BoundedByteBuffer(16)

            // When
            buffer.write("abc")
            buffer.write('d'.code)
            buffer.write("efghijk")
            assertThat(buffer.size).isEqualTo(11)
            assertThat(buffer.remaining).isEqualTo(5)
            buffer.write("lmnopqr")
            buffer.write('s'.code)

            // Then
            assertThat(buffer.size).isEqualTo(16)
            assertThat(buffer.remaining).isZero()
            assertThat(buffer.render(StandardCharsets.UTF_8, 18)).isEqualTo("abcdefghijklmnop... [truncated, 18 bytes total]")
        }

        @Test
        fun `should start at the floor without a hint and double from there, never past the cap`() {
            // What is tested: the array's growth without a hint, every number derived from the floor
            //   (MIN_CAPACITY): single bytes up to the floor, one more across the first doubling, a
            //   chunk that needs more than the doubled array under a cap below the next doubling - and
            //   the floor clipped by a cap below it.
            // Success criteria: nothing allocated before the first byte; the floor after it and still
            //   at the floor's last byte; twice the floor one byte later; the cap (not four times the
            //   floor) once a chunk needs more than twice the floor; the bytes survive every step in
            //   order; a cap of a quarter of the floor allocates that quarter on the first byte.
            // Why it matters: a byte-wise reader without Content-Length doubled up from a 1-byte
            //   array before the floor - an allocation and a copy per doubling; the floor must not cost
            //   a byte of the logged body or exceed the cap.
            // Given
            val floor = BoundedByteBuffer.MIN_CAPACITY
            val cap = 3 * floor + 8
            val buffer = BoundedByteBuffer(cap)
            assertThat(buffer.capacity).isZero()

            // When/Then
            repeat(floor) { buffer.write('a'.code + it % 26) }
            assertThat(buffer.capacity).isEqualTo(floor)
            buffer.write('!'.code)
            assertThat(buffer.capacity).isEqualTo(2 * floor)
            buffer.write("x".repeat(floor + 1))
            assertThat(buffer.capacity).isEqualTo(cap)
            assertThat(buffer.size).isEqualTo(2 * floor + 2)
            assertThat(buffer.render(StandardCharsets.UTF_8, buffer.size.toLong()))
                .isEqualTo(String(CharArray(floor) { 'a' + it % 26 }) + "!" + "x".repeat(floor + 1))

            val small = BoundedByteBuffer(floor / 4)
            small.write('a'.code)
            assertThat(small.capacity).isEqualTo(floor / 4)
        }

        @Test
        fun `should buffer nothing in count-only mode and reject a negative cap`() {
            // What is tested: cap 0 - the measure-only mode - never has room; below 0 is no mode.
            // Success criteria: size stays 0 after writes, remaining is 0, rendering 5 flowed bytes is
            //   the bare note; construction with -1 fails naming the argument.
            // Why it matters: a measure-only capture sits on every exchange and must cost no memory.
            // Given/When
            val buffer = BoundedByteBuffer(0)
            buffer.write("hello")
            buffer.write('!'.code)

            // Then
            assertThat(buffer.size).isZero()
            assertThat(buffer.remaining).isZero()
            assertThat(buffer.render(StandardCharsets.UTF_8, 5)).isEqualTo("... [truncated, 5 bytes total]")
            assertThat(catchThrowable { BoundedByteBuffer(-1) }).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("maxBytes")
        }
    }

    @Nested
    inner class `Range check` {
        @Test
        fun `should reject a range outside the source before clipping, allocating or spending the hint`() {
            // What is tested: a length beyond the array against a 1-byte cap, which the clip alone
            //   would have accepted; a negative length; an offset beyond the array against a FULL
            //   buffer, where a clip to zero would have ignored it.
            // Success criteria: each call throws IndexOutOfBoundsException; nothing is buffered and
            //   nothing allocated after the first; a hint given afterwards still sizes the array.
            // Why it matters: whether a caller's bug surfaced depended on the fill level, and a failed
            //   copy could already have allocated the array and silenced every later hint.
            // Given
            val buffer = BoundedByteBuffer(1)
            val src = byteArrayOf(65, 66, 67)

            // When/Then
            assertThat(catchThrowable { buffer.write(src, 0, 100) }).isInstanceOf(IndexOutOfBoundsException::class.java)
            assertThat(catchThrowable { buffer.write(src, 0, -1) }).isInstanceOf(IndexOutOfBoundsException::class.java)
            assertThat(buffer.size).isZero()
            assertThat(buffer.capacity).isZero()
            buffer.expect(1)
            buffer.write(src, 2, 1)
            assertThat(buffer.capacity).isEqualTo(1)
            assertThat(buffer.remaining).isZero()
            assertThat(catchThrowable { buffer.write(src, 3, 1) }).isInstanceOf(IndexOutOfBoundsException::class.java)
            assertThat(buffer.render(StandardCharsets.UTF_8, 1)).isEqualTo("C")
        }
    }

    @Nested
    inner class `Sizing hint` {
        @Test
        fun `should take the hint only before the first buffered byte`() {
            // What is tested: expect - accepted while nothing is buffered, whatever the value; ignored
            //   once a byte is in the array.
            // Success criteria: the hint reads 5, then 0, then UNKNOWN as set; after one write it stays.
            // Why it matters: the hint sizes the one allocation; a hint that moved afterwards would
            //   suggest a resize that never happens.
            // Given
            val buffer = BoundedByteBuffer(16)

            // When/Then
            buffer.expect(5)
            assertThat(buffer.expectedBytes).isEqualTo(5L)
            buffer.expect(0)
            assertThat(buffer.expectedBytes).isZero()
            buffer.expect(BoundedByteBuffer.UNKNOWN_LENGTH)
            assertThat(buffer.expectedBytes).isEqualTo(BoundedByteBuffer.UNKNOWN_LENGTH)
            buffer.write('a'.code)
            buffer.expect(9)
            assertThat(buffer.expectedBytes).isEqualTo(BoundedByteBuffer.UNKNOWN_LENGTH)
        }

        @Test
        fun `should size the first array by the hint even below the floor`() {
            // What is tested: a hint of an eighth of the floor (MIN_CAPACITY) under a cap of twice
            //   the floor, then exactly that many bytes.
            // Success criteria: the array has the hinted length, not the floor's.
            // Why it matters: a declared length is exact for a well-behaved peer; rounding it up to
            //   the floor would waste the one allocation the hint exists to make right.
            // Given
            val hint = BoundedByteBuffer.MIN_CAPACITY / 8
            val buffer = BoundedByteBuffer(2 * BoundedByteBuffer.MIN_CAPACITY)
            buffer.expect(hint.toLong())

            // When
            buffer.write("h".repeat(hint))

            // Then
            assertThat(buffer.capacity).isEqualTo(hint)
            assertThat(buffer.size).isEqualTo(hint)
        }

        @Test
        fun `should keep every byte whatever the hint says`() {
            // What is tested: a hint below the truth, above the cap, zero and unknown against the same
            //   writes - the hint is peer-controlled and must never clip or break the buffer.
            // Success criteria: each buffer renders the same text for the same input.
            // Why it matters: Content-Length is the peer's word; a lying one may cost an allocation,
            //   never a byte of the logged body.
            listOf(2L, 1L shl 40, 0L, BoundedByteBuffer.UNKNOWN_LENGTH).forEach { hint ->
                // Given
                val buffer = BoundedByteBuffer(8)
                buffer.expect(hint)

                // When: 10 bytes in chunks of 3
                "0123456789".chunked(3).forEach { buffer.write(it) }

                // Then
                assertThat(buffer.render(StandardCharsets.UTF_8, 10)).describedAs("hint $hint").isEqualTo("01234567... [truncated, 10 bytes total]")
            }
        }
    }

    @Nested
    inner class `Truncate` {
        @Test
        fun `should cut the buffer back and take new bytes from there`() {
            // What is tested: truncate to a mark, then writes that overwrite the stale tail.
            // Success criteria: after "abcdef", truncate(3) and "XY" the text is "abcXY" with size 5;
            //   truncating beyond the size or below zero fails.
            // Why it matters: the blocking twin's reset rewinds the capture with the stream; a stale
            //   byte surviving the cut would log bytes the application never read twice.
            // Given
            val buffer = BoundedByteBuffer(16)
            buffer.write("abcdef")

            // When
            buffer.truncate(3)
            buffer.write("XY")

            // Then
            assertThat(buffer.size).isEqualTo(5)
            assertThat(buffer.render(StandardCharsets.UTF_8, 5)).isEqualTo("abcXY")
            assertThat(catchThrowable { buffer.truncate(6) }).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(catchThrowable { buffer.truncate(-1) }).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class `Render` {
        @Test
        fun `should render null for nothing flowed, the text when everything is buffered, and the note otherwise`() {
            // What is tested: the three outcomes of render against the total the twin counted.
            // Success criteria: null for a total of 0 even with nothing allocated; the plain text when
            //   the total equals the size; the prefix and note when the total exceeds it - including a
            //   buffer nothing was written to.
            // Why it matters: the emitter drops a null key, logs a string otherwise; the note is the
            //   reader's only sign that the body was larger than what they see.
            // Given
            val empty = BoundedByteBuffer(8)
            val full = BoundedByteBuffer(8)
            full.write("hello")

            // When/Then
            assertThat(empty.render(StandardCharsets.UTF_8, 0)).isNull()
            assertThat(empty.render(StandardCharsets.UTF_8, 3)).isEqualTo("... [truncated, 3 bytes total]")
            assertThat(full.render(StandardCharsets.UTF_8, 5)).isEqualTo("hello")
            assertThat(full.render(StandardCharsets.UTF_8, 7)).isEqualTo("hello... [truncated, 7 bytes total]")
        }
    }

    @Nested
    inner class `Truncated rendering` {
        @Test
        fun `should decode a prefix beyond the scratch size and leave a cut multi-byte sequence out`() {
            // What is tested: a cap of 3000 bytes filled with "aä😀" units of 7 bytes - the cut falls after
            //   the first byte of an emoji - and a body that flows beyond the cap.
            // Success criteria: the text is 428 whole units plus "aä", then the note; the incomplete
            //   emoji is left out rather than rendered as a replacement character.
            // Why it matters: the scratch buffer holds 1024 chars, so this prefix crosses it several
            //   times; a decoder round that dropped or doubled chars at the refill, or that decoded the
            //   cut sequence as malformed, would corrupt every logged body larger than the scratch.
            // Given
            val unit = "aä😀"
            val cap = 3000
            val buffer = BoundedByteBuffer(cap)
            val body = unit.repeat(500)

            // When
            buffer.write(body)

            // Then
            assertThat(buffer.size).isEqualTo(cap)
            val total = bytes(body).size.toLong()
            assertThat(buffer.render(StandardCharsets.UTF_8, total))
                .isEqualTo(unit.repeat(428) + "aä" + "... [truncated, $total bytes total]")
        }

        @Test
        fun `should keep a surrogate pair whole across the scratch boundary`() {
            // What is tested: 1023 ASCII bytes followed by an emoji, so its surrogate pair would start
            //   at the LAST char of the 1024-char scratch, then more bytes than the cap takes.
            // Success criteria: the emoji and the byte after it render intact before the note.
            // Why it matters: the decoder must refuse the pair when only one char is left, report
            //   overflow WITH progress, and place the pair whole after the refill; splitting it would log
            //   two lone surrogates.
            // Given
            val head = "a".repeat(BoundedByteBuffer.SCRATCH_CHARS - 1) + "😀b"
            val buffer = BoundedByteBuffer(bytes(head).size)

            // When
            buffer.write(head)
            buffer.write("b".repeat(10))

            // Then
            val total = bytes(head).size + 10L
            assertThat(buffer.render(StandardCharsets.UTF_8, total)).isEqualTo(head + "... [truncated, $total bytes total]")
        }

        @Test
        fun `should leave a UTF-16 high surrogate without its low surrogate out`() {
            // What is tested: a UTF-16BE body "a😀b" cut after four bytes - the "a" and the high
            //   surrogate of the emoji.
            // Success criteria: the text is "a" and the note; the lone high surrogate is left out.
            // Why it matters: the boundary case of the charsets whose code units are not bytes - a
            //   decoder finishing with endOfInput = true would render the pending surrogate as a
            //   replacement character.
            // Given
            val body = "a😀b".toByteArray(StandardCharsets.UTF_16BE)
            val buffer = BoundedByteBuffer(4)

            // When
            buffer.write(body, 0, body.size)

            // Then
            assertThat(buffer.render(StandardCharsets.UTF_16BE, body.size.toLong())).isEqualTo("a... [truncated, ${body.size} bytes total]")
        }

        @Test
        fun `should replace malformed bytes inside the prefix`() {
            // What is tested: "ab", a byte that is no UTF-8 at all, "cd", and one flowed byte beyond.
            // Success criteria: the malformed byte renders as U+FFFD; the text around it is intact.
            // Why it matters: only an INCOMPLETE sequence at the cut is left out - a malformed byte in
            //   the middle must show as such, as String(bytes, charset) would render it.
            // Given
            val body = byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 0xFF.toByte(), 'c'.code.toByte(), 'd'.code.toByte())
            val buffer = BoundedByteBuffer(body.size)

            // When
            buffer.write(body, 0, body.size)

            // Then
            assertThat(buffer.render(StandardCharsets.UTF_8, body.size + 1L)).isEqualTo("ab\uFFFDcd... [truncated, 6 bytes total]")
        }

        @Test
        fun `should grow the scratch for a decoder that needs more room than a surrogate pair`() {
            // What is tested: a charset whose decoder turns every byte into three chars and refuses a
            //   step with less room - two bytes buffered, so the scratch starts at two chars.
            // Success criteria: the text is "abcabc" and the note.
            // Why it matters: the first step overflows WITHOUT progress and the scratch must double
            //   (to four); the second step overflows WITH progress and the scratch must be cleared, not
            //   grown - a loop that mistook either case would spin or allocate without bound.
            // Given
            val buffer = BoundedByteBuffer(2)

            // When
            buffer.write("xy")

            // Then
            assertThat(buffer.render(TripletCharset, 3)).isEqualTo("abcabc... [truncated, 3 bytes total]")
        }
    }

    /** Every byte decodes to "abc" in one step; the decoder refuses a step with fewer than three slots. */
    private object TripletCharset : Charset("x-legatium-triplet", null) {
        override fun contains(cs: Charset): Boolean = cs === this

        override fun newEncoder(): CharsetEncoder = throw UnsupportedOperationException("decode only")

        override fun newDecoder(): CharsetDecoder =
            object : CharsetDecoder(this, 3f, 3f) {
                override fun decodeLoop(
                    input: ByteBuffer,
                    output: CharBuffer,
                ): CoderResult {
                    while (input.hasRemaining()) {
                        if (output.remaining() < 3) {
                            return CoderResult.OVERFLOW
                        }
                        input.get()
                        output.put("abc")
                    }
                    return CoderResult.UNDERFLOW
                }
            }
    }
}
