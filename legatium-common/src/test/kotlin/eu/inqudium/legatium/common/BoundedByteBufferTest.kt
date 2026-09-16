package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/**
 * The shared buffer beneath both twins' captures: the cap, the growth and sizing hint, the cut-back for
 * a reset, and the rendering. The twins' own tests drive it through their captures (count, read state,
 * concurrency, the truncation at a character boundary); this test owns the buffer's contract alone.
 */
class BoundedByteBufferTest {
    private fun bytes(text: String) = text.toByteArray(StandardCharsets.UTF_8)

    private fun BoundedByteBuffer.write(text: String) = write(bytes(text), 0, text.length)

    @Nested
    inner class `Cap and growth` {
        @Test
        fun `should keep the first bytes up to the cap through both write paths and grow in between`() {
            // What is tested: writes in chunks that cross every doubling step of a 16-byte cap, single
            //   bytes among them, and a chunk that is clipped at the cap.
            // Success criteria: size and remaining follow the writes exactly; rendering 18 flowed
            //   bytes shows the first 16 in order with the note.
            // Why it matters: the bytes the twins log come out of this array; a growth step that
            //   dropped or reordered a byte would corrupt every logged body above the first chunk.
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
}
