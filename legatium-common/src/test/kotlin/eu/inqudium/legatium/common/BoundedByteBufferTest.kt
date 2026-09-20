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
import java.nio.charset.CodingErrorAction
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

            // When: 11 bytes in three writes
            buffer.write("abc")
            buffer.write('d'.code)
            buffer.write("efghijk")

            // Then: 11 buffered, 5 left
            assertThat(buffer.size).isEqualTo(11)
            assertThat(buffer.remaining).isEqualTo(5)

            // When: 8 more bytes, of which 5 fit
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
        fun `should keep doubling past 1 GiB instead of growing by the need`() {
            // What is tested: the growth arithmetic alone (the array itself would need a 2 GiB heap):
            //   a current length of 1 GiB, a need of one byte more, no hint, a cap of Int.MAX_VALUE.
            // Success criteria: the next length is the cap - the doubling clipped - not the need.
            // Why it matters: an Int doubling of 1 GiB is negative; maxOf would then pick the need,
            //   and every further byte would allocate and copy the whole array.
            // Given
            val current = 1 shl 30

            // When
            val next = BoundedByteBuffer.grownLength(Int.MAX_VALUE, current, current + 1, 0)

            // Then
            assertThat(next).isEqualTo(Int.MAX_VALUE)
        }

        @Test
        fun `should buffer nothing in count-only mode and reject a negative cap`() {
            // What is tested: cap 0 - the measure-only mode - never has room, whatever the hint; below
            //   0 is no mode.
            // Success criteria: size stays 0 after writes, remaining is 0, NO array is allocated,
            //   rendering 5 flowed bytes is the bare note; construction with -1 fails naming the argument.
            // Why it matters: a measure-only capture sits on every exchange and must cost no memory -
            //   neither for the bytes nor, should it ever be rendered, for a decoder.
            // Given/When
            val buffer = BoundedByteBuffer(0)
            buffer.expect(5)
            buffer.write("hello")
            buffer.write('!'.code)

            // Then
            assertThat(buffer.size).isZero()
            assertThat(buffer.remaining).isZero()
            assertThat(buffer.capacity).isZero()
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
        fun `should cap what a hint allocates and double from there`() {
            // What is tested: a hint of Long.MAX_VALUE under a cap of four times MAX_HINTED_CAPACITY,
            //   then one byte, then enough bytes to outgrow the hinted array.
            // Success criteria: the first byte allocates MAX_HINTED_CAPACITY, not the cap; the array
            //   doubles once the hinted length is exceeded.
            // Why it matters: Content-Length is the peer's word - without the ceiling one byte of a
            //   body declared huge reserved the whole cap, and many such exchanges at once would have
            //   held the cap each.
            // Given
            val ceiling = BoundedByteBuffer.MAX_HINTED_CAPACITY
            val buffer = BoundedByteBuffer(4 * ceiling)
            buffer.expect(Long.MAX_VALUE)

            // When/Then
            buffer.write('a'.code)
            assertThat(buffer.capacity).isEqualTo(ceiling)
            buffer.write("b".repeat(ceiling))
            assertThat(buffer.capacity).isEqualTo(2 * ceiling)
            assertThat(buffer.size).isEqualTo(ceiling + 1)
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

        @Test
        fun `should reject a total below the buffered size`() {
            // What is tested: five buffered bytes rendered with a total of 3, of 0 and of -1.
            // Success criteria: each call fails naming the total; the buffer stays renderable.
            // Why it matters: a total below the size means the twin's count and the buffer disagree - a
            //   silent render would log a partial body as complete (or hide it as null) and the emitter's
            //   fail-open guard would never learn of the bug.
            // Given
            val buffer = BoundedByteBuffer(8)
            buffer.write("hello")

            // When/Then
            listOf(3L, 0L, -1L).forEach { total ->
                assertThat(catchThrowable { buffer.render(StandardCharsets.UTF_8, total) })
                    .describedAs("total $total")
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("total")
            }
            assertThat(buffer.render(StandardCharsets.UTF_8, 5)).isEqualTo("hello")
        }
    }

    @Nested
    inner class `Truncated rendering` {
        private fun utf16(text: String) = text.toByteArray(StandardCharsets.UTF_16BE)

        private fun note(total: Long) = "... [truncated, $total bytes total]"

        @Test
        fun `should cut a UTF-8 prefix before an incomplete sequence and keep a complete one`() {
            // What is tested: the UTF-8 fast path against every cut through "aä€😀": after each byte
            //   of the four-byte emoji, after the first byte of the two- and three-byte characters, and
            //   at every character boundary.
            // Success criteria: a cut inside a character renders the characters before it; a cut at a
            //   boundary renders every character up to it.
            // Why it matters: the fast path finds the cut itself instead of asking the decoder for the
            //   whole prefix - a cut one byte off would drop a complete character or show a broken one.
            // Given
            val text = "aä€😀"
            val body = bytes(text) + bytes("!")
            val boundaries = mapOf(0 to "", 1 to "a", 3 to "aä", 6 to "aä€", 10 to "aä€😀")

            // When/Then
            (1 until body.size).forEach { cap ->
                val buffer = BoundedByteBuffer(cap)
                buffer.write(body, 0, body.size)
                val expected = boundaries.filterKeys { it <= cap }.maxBy { it.key }.value
                assertThat(buffer.render(StandardCharsets.UTF_8, body.size.toLong())).describedAs("cap $cap").isEqualTo(expected + note(body.size.toLong()))
            }
        }

        @Test
        fun `should cut a UTF-8 prefix exactly where the decoder would stop`() {
            // What is tested: utf8Cut against the JDK decoder over every prefix length of a sample that
            //   mixes one- to four-byte characters, a malformed lead with a bad second byte (E0 80),
            //   stray continuation bytes and a byte that is no UTF-8 at all.
            // Success criteria: for every length the cut equals the input position the decoder leaves
            //   with endOfInput = false over the whole prefix.
            // Why it matters: the fast path and the generic path must render the same text for the same
            //   bytes; this pins the fast path to the decoder's own rules, malformed tails included.
            // Given
            val sample = bytes("aä€😀") + byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0x80.toByte(), 0xFF.toByte()) + bytes("z😀ä")

            // When/Then
            (1..sample.size).forEach { length ->
                val input = ByteBuffer.wrap(sample, 0, length)
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(input, CharBuffer.allocate(length), false)
                assertThat(BoundedByteBuffer.utf8Cut(sample, length)).describedAs("length $length").isEqualTo(input.position())
            }
        }

        @Test
        fun `should replace a malformed UTF-8 tail instead of leaving it out`() {
            // What is tested: "ab" followed by E0 80 - a three-byte lead whose second byte is invalid -
            //   and "ab" followed by two stray continuation bytes, each cut right there.
            // Success criteria: both render "ab" and two replacement characters before the note.
            // Why it matters: only a WELL-FORMED incomplete tail is left out; a malformed one is not the
            //   capture's cut but the peer's bytes, and must show as the decoder shows it.
            listOf(byteArrayOf(0xE0.toByte(), 0x80.toByte()), byteArrayOf(0x80.toByte(), 0x80.toByte())).forEach { tail ->
                // Given
                val body = bytes("ab") + tail
                val buffer = BoundedByteBuffer(body.size)

                // When
                buffer.write(body, 0, body.size)

                // Then
                assertThat(buffer.render(StandardCharsets.UTF_8, body.size + 1L)).isEqualTo("ab\uFFFD\uFFFD" + note(body.size + 1L))
            }
        }

        @Test
        fun `should decode a prefix beyond the scratch size and leave a cut code unit out`() {
            // What is tested: the generic path - UTF-16BE, which has no fast path - with a prefix of 1500
            //   chars, so it crosses the 1024-char scratch, cut after the high surrogate of an emoji.
            // Success criteria: the 1500 chars and the note; the pending surrogate is left out.
            // Why it matters: a decoder round that dropped or doubled chars at the refill, or that
            //   decoded the cut unit as malformed, would corrupt every logged body larger than the scratch.
            // Given
            val head = "a".repeat(1500)
            val body = utf16(head + "😀b")
            val cap = utf16(head).size + 2

            // When
            val buffer = BoundedByteBuffer(cap)
            buffer.write(body, 0, body.size)

            // Then
            assertThat(buffer.size).isEqualTo(cap)
            assertThat(buffer.render(StandardCharsets.UTF_16BE, body.size.toLong())).isEqualTo(head + note(body.size.toLong()))
        }

        @Test
        fun `should keep a surrogate pair whole across the scratch boundary`() {
            // What is tested: the generic path (UTF-16BE) with 1023 chars followed by an emoji, so its
            //   surrogate pair would start at the LAST slot of the 1024-char scratch, then more bytes than
            //   the cap takes.
            // Success criteria: the emoji and the char after it render intact before the note.
            // Why it matters: the decoder must refuse the pair when only one slot is left, report
            //   overflow WITH progress, and place the pair whole after the refill; splitting it would log
            //   two lone surrogates.
            // Given
            val head = "a".repeat(BoundedByteBuffer.SCRATCH_CHARS - 1) + "😀b"
            val buffer = BoundedByteBuffer(utf16(head).size)

            // When
            buffer.write(utf16(head), 0, utf16(head).size)
            buffer.write(utf16("bbbbb"), 0, 10)

            // Then
            val total = utf16(head).size + 10L
            assertThat(buffer.render(StandardCharsets.UTF_16BE, total)).isEqualTo(head + note(total))
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
            val body = utf16("a😀b")
            val buffer = BoundedByteBuffer(4)

            // When
            buffer.write(body, 0, body.size)

            // Then
            assertThat(buffer.render(StandardCharsets.UTF_16BE, body.size.toLong())).isEqualTo("a" + note(body.size.toLong()))
        }

        @Test
        fun `should replace malformed bytes inside the prefix on both paths`() {
            // What is tested: a byte that is no UTF-8 between "ab" and "cd" on the fast path, and a lone
            //   low surrogate between "a" and "b" in UTF-16BE on the generic path, one flowed byte beyond.
            // Success criteria: the malformed unit renders as U+FFFD; the text around it is intact.
            // Why it matters: only an INCOMPLETE unit at the cut is left out - a malformed one in the
            //   middle must show as such, as String(bytes, charset) would render it.
            // Given
            val utf8 = bytes("ab") + byteArrayOf(0xFF.toByte()) + bytes("cd")
            val utf16 = utf16("a") + byteArrayOf(0xDC.toByte(), 0x00) + utf16("b")

            // When
            val fast = BoundedByteBuffer(utf8.size).apply { write(utf8, 0, utf8.size) }
            val generic = BoundedByteBuffer(utf16.size).apply { write(utf16, 0, utf16.size) }

            // Then
            assertThat(fast.render(StandardCharsets.UTF_8, utf8.size + 1L)).isEqualTo("ab\uFFFDcd" + note(utf8.size + 1L))
            assertThat(generic.render(StandardCharsets.UTF_16BE, utf16.size + 1L)).isEqualTo("a\uFFFDb" + note(utf16.size + 1L))
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
            assertThat(buffer.render(TripletCharset, 3)).isEqualTo("abcabc" + note(3))
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
