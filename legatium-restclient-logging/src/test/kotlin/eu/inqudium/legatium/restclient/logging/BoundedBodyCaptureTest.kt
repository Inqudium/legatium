package eu.inqudium.legatium.restclient.logging

import eu.inqudium.legatium.common.BodyReadState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** The bounded capture of the blocking twin: count-only mode, byte-bounded truncation, the read state. */
class BoundedBodyCaptureTest {
    private fun bytes(text: String) = text.toByteArray(StandardCharsets.UTF_8)

    @Nested
    inner class `Counting and bounding` {
        @Test
        fun `should count every byte but buffer only up to the limit`() {
            // Given: a 4-byte cap
            val capture = BoundedBodyCapture(4)

            // When: 10 bytes flow in two chunks and one single byte
            capture.capture(bytes("0123"), 0, 4)
            capture.capture(bytes("45678"), 0, 5)
            capture.capture('9'.code)

            // Then: exact total, truncated text
            assertThat(capture.totalBytes).isEqualTo(10L)
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("0123... [truncated, 10 bytes total]")
        }

        @Test
        fun `should buffer nothing in count-only mode and still count everything`() {
            // Given: limit 0 - the measure-only mode
            val capture = BoundedBodyCapture(0)

            // When
            capture.capture(bytes("hello"), 0, 5)

            // Then
            assertThat(capture.totalBytes).isEqualTo(5L)
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("... [truncated, 5 bytes total]")
        }

        @Test
        fun `should report a zero-byte body as absent`() {
            // Given/When/Then
            assertThat(BoundedBodyCapture(8).loggedValue(StandardCharsets.UTF_8)).isNull()
        }
    }

    @Nested
    inner class `Truncation at a character boundary` {
        @Test
        fun `should drop an incomplete trailing UTF-8 sequence instead of decoding a replacement character`() {
            // What is tested: byte-bounded truncation of multi-byte text - the cap counts bytes, so it can
            //   split a character.
            // Success criteria: with a 2-byte cap over "hé" (3 bytes: 68 c3 a9) the logged prefix is
            //   "h", not "h�"; the byte count stays exact.
            // Why it matters: a replacement character in the logged prefix is corruption the reader
            //   cannot distinguish from corrupt input.
            // Given
            val capture = BoundedBodyCapture(2)
            val body = bytes("hé")
            capture.capture(body, 0, body.size)

            // When/Then
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("h... [truncated, 3 bytes total]")
        }

        @Test
        fun `should drop an incomplete trailing sequence of another variable-width charset`() {
            // Given: Shift_JIS "aあ" = 61 82 a0, capped at 2 bytes
            val shiftJis = Charset.forName("Shift_JIS")
            val capture = BoundedBodyCapture(2)
            val body = "aあ".toByteArray(shiftJis)
            capture.capture(body, 0, body.size)

            // When/Then
            assertThat(body).hasSize(3)
            assertThat(capture.loggedValue(shiftJis)).isEqualTo("a... [truncated, 3 bytes total]")
        }

        @Test
        fun `should keep a complete multi-byte character that ends exactly at the cap`() {
            // Given
            val capture = BoundedBodyCapture(2)
            val body = bytes("éx")
            capture.capture(body, 0, body.size)

            // When/Then
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("é... [truncated, 3 bytes total]")
        }

        @Test
        fun `should still replace malformed bytes inside the prefix`() {
            // Given
            val capture = BoundedBodyCapture(3)
            val body = byteArrayOf(0x61, 0xa9.toByte(), 0x62, 0x63)
            capture.capture(body, 0, body.size)

            // When/Then
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("a�b... [truncated, 4 bytes total]")
        }
    }

    @Nested
    inner class `Read state` {
        @Test
        fun `should start unread and move to partial on start and to complete on completion, never backwards`() {
            // Given
            val capture = BoundedBodyCapture(8)
            assertThat(capture.readState).isEqualTo(BodyReadState.UNREAD)

            // When/Then
            capture.markStarted()
            assertThat(capture.readState).isEqualTo(BodyReadState.PARTIAL)
            capture.markCompleted()
            assertThat(capture.readState).isEqualTo(BodyReadState.COMPLETE)
            capture.markStarted()
            assertThat(capture.readState).isEqualTo(BodyReadState.COMPLETE)
        }
    }
}
