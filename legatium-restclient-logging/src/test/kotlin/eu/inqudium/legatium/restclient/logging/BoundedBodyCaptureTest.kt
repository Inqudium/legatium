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
            // What is tested: both capture overloads against one 4-byte cap - the chunk write clips
            //   to the remaining room, the single-byte write checks the buffer size, totalBytes
            //   advances regardless.
            // Success criteria: totalBytes is 10 while loggedValue renders the four buffered bytes
            //   plus the "... [truncated, 10 bytes total]" note.
            // Why it matters: the size summaries need the exact total and the log line must never
            //   hold more than max-body-bytes, whatever chunking the engine's stream uses.
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
            // What is tested: limit 0, the mode newCaptures installs for measure-*-body-size
            //   without body logging - room is never positive, so the write is skipped, but the count
            //   advances.
            // Success criteria: totalBytes is 5 and loggedValue is the bare truncation note without
            //   a prefix.
            // Why it matters: a measure-only capture is installed on every exchange and must cost
            //   no buffer memory; the exact total is what the size summary records.
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
            // What is tested: the totalBytes == 0 short-circuit of loggedValue on a capture nothing
            //   flowed through.
            // Success criteria: null, not an empty string and not a "[truncated, 0 bytes total]"
            //   note.
            // Why it matters: the emitter's addKeyValueIfPresent drops a null, so a bodiless
            //   exchange gets no body key at all instead of an empty one that looks like an empty
            //   body.
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
            // What is tested: decodeTruncated with a charset other than UTF-8 - Shift_JIS "aあ" (61
            //   82 a0) cut at 2 bytes leaves the lead byte 82 dangling; the underflow handling must
            //   be charset-generic.
            // Success criteria: the prefix is "a" followed by the note for 3 bytes total; the
            //   dangling lead byte yields no replacement character.
            // Why it matters: the response body is decoded with the charset the peer declares, so
            //   the boundary logic must hold for every variable-width encoding, not only UTF-8.
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
            // What is tested: the boundary case of the truncation decoder - "éx" capped at 2 bytes
            //   ends exactly after the 2-byte é (c3 a9), so nothing is incomplete.
            // Success criteria: the prefix is the whole "é" followed by the note for 3 bytes total.
            // Why it matters: the underflow handling must drop only an incomplete tail; dropping a
            //   complete character would lose a byte the cap admitted.
            // Given
            val capture = BoundedBodyCapture(2)
            val body = bytes("éx")
            capture.capture(body, 0, body.size)

            // When/Then
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("é... [truncated, 3 bytes total]")
        }

        @Test
        fun `should still replace malformed bytes inside the prefix`() {
            // What is tested: the REPLACE action of the truncation decoder - 0xa9 is a stray
            //   continuation byte between "a" and "b", well inside the 3-byte cap.
            // Success criteria: the prefix renders "a�b" with the note for 4 bytes total; the
            //   malformed byte is replaced, not dropped, and does not abort the decoding.
            // Why it matters: endOfInput=false must suppress only the trailing underflow; genuinely
            //   broken input must render as String(bytes, charset) would, so the log shows the
            //   corruption where it is.
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
            // What is tested: the readState transitions - UNREAD at construction, PARTIAL on
            //   markStarted, COMPLETE on markCompleted, and markStarted's UNREAD guard after
            //   completion.
            // Success criteria: the four observations in that order; the last markStarted leaves
            //   COMPLETE in place.
            // Why it matters: the state becomes the state tag of adapter.response.body.read - a
            //   fully consumed body reported as partial would show payload discarded that was not.
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

        @Test
        fun `should complete when the byte count reaches the declared length without an EOF`() {
            // What is tested: expectBytes - a declared length completes the read state the moment the
            //   count reaches it, through either capture overload, with no markCompleted call.
            // Success criteria: PARTIAL after 5 of 6 bytes, COMPLETE after the sixth, and COMPLETE
            //   stays when more bytes than declared arrive.
            // Why it matters: a length-aware reader (Spring's ByteArrayHttpMessageConverter,
            //   readNBytes) never asks for the EOF; without this rule its complete reads counted as
            //   partial.
            // Given: a capture told to expect 6 bytes
            val capture = BoundedBodyCapture(8)
            capture.expectBytes(6)
            capture.markStarted()

            // When/Then
            capture.capture(bytes("01234"), 0, 5)
            assertThat(capture.readState).isEqualTo(BodyReadState.PARTIAL)
            capture.capture('5'.code)
            assertThat(capture.readState).isEqualTo(BodyReadState.COMPLETE)
            capture.capture('6'.code)
            assertThat(capture.readState).isEqualTo(BodyReadState.COMPLETE)
            assertThat(capture.totalBytes).isEqualTo(7L)
        }

        @Test
        fun `should complete a declared zero-length body when the stream is opened`() {
            // What is tested: markStarted with a declared length of zero - a length-aware reader
            //   opens the stream and reads nothing, which is the whole body.
            // Success criteria: COMPLETE right after markStarted; with an unknown length the same
            //   call yields PARTIAL.
            // Why it matters: an empty declared body must not count as consumption that stopped
            //   early.
            // Given/When/Then: declared zero
            val declaredEmpty = BoundedBodyCapture(8)
            declaredEmpty.expectBytes(0)
            declaredEmpty.markStarted()
            assertThat(declaredEmpty.readState).isEqualTo(BodyReadState.COMPLETE)

            // And: unknown length stays the EOF rule
            val unknown = BoundedBodyCapture(8)
            unknown.expectBytes(BoundedBodyCapture.UNKNOWN_LENGTH)
            unknown.markStarted()
            assertThat(unknown.readState).isEqualTo(BodyReadState.PARTIAL)
        }
    }
}
