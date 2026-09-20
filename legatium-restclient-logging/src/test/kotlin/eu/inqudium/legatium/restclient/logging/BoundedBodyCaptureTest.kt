package eu.inqudium.legatium.restclient.logging

import eu.inqudium.legatium.common.BodyReadState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
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
        fun `should grow the buffer with the writes and clip it at the limit`() {
            // What is tested: the lazily allocated, doubling buffer behind the capture - a run of
            //   3-byte chunks crosses every growth step of a 16-byte cap and the last one is clipped.
            // Success criteria: the logged text is the first 16 bytes in order followed by the note
            //   for 18 bytes total; a body below the cap renders whole, without a note.
            // Why it matters: the buffer replaced a ByteArrayOutputStream so a reset can cut it back;
            //   the growth path must keep every byte in order and never hold more than the cap.
            // Given: a 16-byte cap
            val capture = BoundedBodyCapture(16)

            // When: 18 bytes flow in 3-byte chunks
            "abcdefghijklmnopqr".chunked(3).forEach { capture.capture(bytes(it), 0, 3) }

            // Then
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("abcdefghijklmnop... [truncated, 18 bytes total]")

            // And: a body within the cap, written in growing chunks, renders whole
            val small = BoundedBodyCapture(16)
            small.capture(bytes("a"), 0, 1)
            small.capture(bytes("bcd"), 0, 3)
            small.capture(bytes("efghijk"), 0, 7)
            assertThat(small.loggedValue(StandardCharsets.UTF_8)).isEqualTo("abcdefghijk")
        }

        @Test
        fun `should reject a negative limit at construction`() {
            // What is tested: the limit's lower bound as an executable precondition - 0 is count-only
            //   mode, below that is no mode at all.
            // Success criteria: construction fails naming the argument; 0 constructs.
            // Why it matters: a negative limit behaved silently like count-only (room never positive);
            //   production never passes one (maxBodyBytes must be positive), so a silent acceptance
            //   could only hide a bug.
            // Given/When/Then
            assertThat(catchThrowable { BoundedBodyCapture(-1) }).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("maxBytes")
            assertThat(BoundedBodyCapture(0).totalBytes).isZero()
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
    inner class `Mark and reset` {
        @Test
        fun `should rewind the count and the buffer to the mark so replayed bytes count once`() {
            // What is tested: mark/reset against a 6-byte cap - the mark is taken after 3 bytes, 5 more
            //   flow (crossing the cap), the reset rewinds, and the same 5 bytes are read again.
            // Success criteria: right after the reset the total is 3 and the logged text is "abc";
            //   after the replay the total is 8 and the text is the first 6 bytes with the note for 8 -
            //   exactly what a single read of the 8 bytes yields.
            // Why it matters: the tee stream forwards the engine stream's reset, so a converter that
            //   peeks and rewinds (Spring's IntrospectingClientHttpResponse on a buffered response)
            //   must not double the size sample or duplicate the logged prefix.
            // Given: 3 bytes, then a mark
            val capture = BoundedBodyCapture(6)
            capture.capture(bytes("abc"), 0, 3)
            capture.mark()

            // When: 5 bytes flow
            capture.capture(bytes("defgh"), 0, 5)

            // Then: counted
            assertThat(capture.totalBytes).isEqualTo(8L)

            // When: the stream is reset
            capture.reset()

            // Then: back at the mark
            assertThat(capture.totalBytes).isEqualTo(3L)
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("abc")

            // And: the replay counts once
            capture.capture(bytes("de"), 0, 2)
            capture.capture('f'.code)
            capture.capture(bytes("gh"), 0, 2)
            assertThat(capture.totalBytes).isEqualTo(8L)
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("abcdef... [truncated, 8 bytes total]")
        }

        @Test
        fun `should rewind to the start of an opened stream when no mark was taken`() {
            // What is tested: reset without a mark - a ByteArrayInputStream rewinds to its beginning,
            //   and the capture must follow: nothing counted, nothing buffered, the state the open
            //   left behind.
            // Success criteria: after markStarted, 4 bytes and a reset, the total is 0, loggedValue is
            //   null and the state is PARTIAL - opened, nothing read.
            // Why it matters: the InputStream contract makes reset legal without a mark on a
            //   mark-capable stream; the capture must not keep bytes the application will read again.
            // Given: an opened stream with 4 bytes read
            val capture = BoundedBodyCapture(8)
            capture.markStarted()
            capture.capture(bytes("abcd"), 0, 4)

            // When
            capture.reset()

            // Then
            assertThat(capture.totalBytes).isZero()
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isNull()
            assertThat(capture.readState).isEqualTo(BodyReadState.PARTIAL)
        }

        @Test
        fun `should rewind the read state with the bytes and complete again on the replay`() {
            // What is tested: the read state is part of the mark - a body read to its declared end,
            //   then reset, is no longer complete until the replay reaches the end again.
            // Success criteria: COMPLETE after 4 of 4 declared bytes, PARTIAL after the reset to the
            //   mark at 1 byte, COMPLETE again after 3 replayed bytes.
            // Why it matters: a peek-and-rewind that left COMPLETE in place would count a body the
            //   application then abandoned as consumed.
            // Given: 4 declared bytes, a mark after the first
            val capture = BoundedBodyCapture(8)
            capture.expectBytes(4)
            capture.markStarted()
            capture.capture('a'.code)
            capture.mark()

            // When/Then
            capture.capture(bytes("bcd"), 0, 3)
            assertThat(capture.readState).isEqualTo(BodyReadState.COMPLETE)
            capture.reset()
            assertThat(capture.readState).isEqualTo(BodyReadState.PARTIAL)
            assertThat(capture.totalBytes).isEqualTo(1L)
            capture.capture(bytes("bcd"), 0, 3)
            assertThat(capture.readState).isEqualTo(BodyReadState.COMPLETE)
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("abcd")
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
            // Given: a fresh capture
            val capture = BoundedBodyCapture(8)

            // When/Then: UNREAD at construction, then one observation per transition
            assertThat(capture.readState).isEqualTo(BodyReadState.UNREAD)
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
        fun `should complete a body of declared length zero at once, without the stream being opened`() {
            // What is tested: expectBytes(0) - an answer that carries no body (a 204, a
            //   Content-Length of 0) completes the read state the moment that is known, because
            //   Spring's clients never open such a body and no later mark could.
            // Success criteria: COMPLETE right after expectBytes(0) with no markStarted call, and
            //   still COMPLETE after one; with an unknown length markStarted yields PARTIAL.
            // Why it matters: before this rule every 204 counted as unread - a route of deletes and
            //   updates read as 100 % discarded payload on the counter that exists to show exactly
            //   that, and differently from the reactive twin, whose empty body flux completes.
            // Given/When/Then: declared zero, never opened
            val declaredEmpty = BoundedBodyCapture(8)
            declaredEmpty.expectBytes(0)
            assertThat(declaredEmpty.readState).isEqualTo(BodyReadState.COMPLETE)
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
