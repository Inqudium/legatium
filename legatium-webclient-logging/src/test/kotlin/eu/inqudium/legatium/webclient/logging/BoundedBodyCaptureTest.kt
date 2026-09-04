package eu.inqudium.legatium.webclient.logging

import eu.inqudium.legatium.common.BodyReadState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import reactor.core.publisher.Flux
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * The freeze contract of the reactive [BoundedBodyCapture]: the emission freezes the capture FIRST, so a
 * body chunk delivered after a cancellation can no longer move the logged text or the size sample.
 */
class BoundedBodyCaptureTest {
    private fun bytes(text: String) = text.toByteArray(StandardCharsets.UTF_8)

    @Nested
    inner class `Freeze semantics` {
        @Test
        fun `should ignore every mutation after freeze and keep the snapshot stable`() {
            // What is tested: post-freeze capture and count are no-ops.
            // Success criteria: the logged value and totalBytes after the late mutations equal the
            //   values at freeze time.
            // Why it matters: the emitter reads body text and size as two separate calls; a mutation
            //   between them - or during them - would make the logged body and the metric disagree.
            // Given: a capture with content, frozen
            val capture = BoundedBodyCapture(8)
            capture.capture(bytes("hello"), 0, 5)
            capture.freeze()

            // When: late tee calls arrive
            capture.capture(bytes("late"), 0, 4)
            capture.count(100)

            // Then: the snapshot is what was frozen
            assertThat(capture.isFrozen).isTrue()
            assertThat(capture.totalBytes).isEqualTo(5L)
            assertThat(capture.remainingCapacity()).isEqualTo(0)
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("hello")
        }

        @Test
        fun `should freeze idempotently and keep a zero-byte capture absent`() {
            // What is tested: freeze on a capture that never saw a byte, called twice.
            // Success criteria: the second freeze neither throws nor changes anything - loggedValue
            //   stays null and totalBytes stays 0.
            // Why it matters: the emitter freezes both captures unconditionally; a null body field
            //   is what lets a count-only or bodiless exchange omit the key instead of logging an
            //   empty string.
            // Given: an untouched capture, frozen twice
            val capture = BoundedBodyCapture(8)
            capture.freeze()
            capture.freeze()

            // When/Then: still absent, still zero
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isNull()
            assertThat(capture.totalBytes).isEqualTo(0L)
        }
    }

    @Nested
    inner class `Late delivery through the tee` {
        @Test
        fun `should not let a buffer delivered after the freeze reach the capture`() {
            // What is tested: the hand-off the cancellation race exercises - the body tee is still
            //   subscribed when the emission freezes the capture, and the publisher then delivers an
            //   already-requested buffer.
            // Success criteria: the buffer passes the tee (downstream is unaffected) but the capture's
            //   text and count are unchanged.
            // Why it matters: `doFinally(CANCEL)` runs immediately after cancellation is forwarded while
            //   an onNext may still be in flight; without the freeze the log snapshot would be taken
            //   from a buffer that another thread is mutating.
            // Given: a teed response body whose publisher is driven by hand
            val capture = BoundedBodyCapture(32)
            val publisher = ManualPublisher()
            Flux.from(publisher).map { tee(capture, it) }.subscribe()
            publisher.emit(DefaultDataBufferFactory.sharedInstance.wrap(bytes("before")))

            // When: the emission freezes the capture, then a late buffer arrives
            capture.freeze()
            publisher.emit(DefaultDataBufferFactory.sharedInstance.wrap(bytes("-late")))

            // Then: the late buffer left no trace in the capture
            assertThat(capture.totalBytes).isEqualTo(6L)
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("before")
        }
    }

    @Nested
    inner class `Truncation at a character boundary` {
        @Test
        fun `should drop an incomplete trailing UTF-8 sequence instead of decoding a replacement character`() {
            // What is tested: byte-bounded truncation of multi-byte text - the cap counts bytes, so it can
            //   split a character.
            // Success criteria: with a 2-byte cap over "h\u00e9" (3 bytes: 68 c3 a9) the logged prefix is
            //   "h", not "h\uFFFD"; the byte count stays exact.
            // Why it matters: a replacement character in the logged prefix is corruption the reader
            //   cannot distinguish from corrupt input.
            // Given: a 2-byte capture over a 3-byte text
            val capture = BoundedBodyCapture(2)
            val body = bytes("h\u00e9")
            capture.capture(body, 0, body.size)

            // When/Then
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("h... [truncated, 3 bytes total]")
        }

        @Test
        fun `should drop an incomplete trailing sequence of another variable-width charset`() {
            // What is tested: decodeTruncated with the charset the peer declared instead of UTF-8 -
            //   Shift_JIS, where the second character is a two-byte sequence the 2-byte cap splits.
            // Success criteria: the logged prefix is "a" plus the truncation note with the exact
            //   3-byte total, no replacement character.
            // Why it matters: the prefix decoding must follow the declared charset, not a UTF-8
            //   assumption, or every non-UTF-8 peer would log a corrupted tail.
            // Given: Shift_JIS "a\u3042" = 61 82 a0, capped at 2 bytes
            val shiftJis = Charset.forName("Shift_JIS")
            val capture = BoundedBodyCapture(2)
            val body = "a\u3042".toByteArray(shiftJis)
            capture.capture(body, 0, body.size)

            // When/Then: only the complete character survives
            assertThat(body).hasSize(3)
            assertThat(capture.loggedValue(shiftJis)).isEqualTo("a... [truncated, 3 bytes total]")
        }

        @Test
        fun `should keep a complete multi-byte character that ends exactly at the cap`() {
            // What is tested: the boundary case of the byte cap - the last captured byte completes
            //   a character.
            // Success criteria: the character is logged in full, followed by the truncation note
            //   with the 3-byte total.
            // Why it matters: underflow handling must not eat a character that happens to end on
            //   the cap, or the logged prefix would be one character short of what was actually
            //   captured.
            // Given: "\u00e9" (2 bytes) capped at 2, followed by more
            val capture = BoundedBodyCapture(2)
            val body = bytes("\u00e9x")
            capture.capture(body, 0, body.size)

            // When/Then
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("\u00e9... [truncated, 3 bytes total]")
        }

        @Test
        fun `should still replace malformed bytes inside the prefix`() {
            // What is tested: the distinction decodeTruncated draws between an incomplete TAIL
            //   (underflow, dropped) and a malformed byte INSIDE the prefix (replaced).
            // Success criteria: the lone continuation byte renders as U+FFFD while the surrounding
            //   characters and the truncation note stay intact.
            // Why it matters: genuinely corrupt input must still show as corrupt in the log; only
            //   the artefact of the byte cap is suppressed.
            // Given: a lone continuation byte in the middle, under truncation
            val capture = BoundedBodyCapture(3)
            val body = byteArrayOf(0x61, 0xa9.toByte(), 0x62, 0x63)
            capture.capture(body, 0, body.size)

            // When/Then: the malformed byte is replaced, the prefix is otherwise intact
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("a\uFFFDb... [truncated, 4 bytes total]")
        }
    }

    @Nested
    inner class `Read state` {
        @Test
        fun `should start unread and move to partial on start and to complete on completion, never backwards`() {
            // What is tested: the read-state machine of the response capture - markStarted and
            //   markCompleted.
            // Success criteria: UNREAD before any mark, PARTIAL after the subscription, COMPLETE
            //   after the completion signal, and a later markStarted leaves COMPLETE untouched.
            // Why it matters: the state becomes the `state` tag of adapter.response.body.read; a
            //   regression to PARTIAL would report fully consumed bodies as discarded payload.
            // Given: a fresh capture
            val capture = BoundedBodyCapture(8)
            assertThat(capture.readState).isEqualTo(BodyReadState.UNREAD)

            // When/Then: start -> partial, completion -> complete, a later start does not regress
            capture.markStarted()
            assertThat(capture.readState).isEqualTo(BodyReadState.PARTIAL)
            capture.markCompleted()
            assertThat(capture.readState).isEqualTo(BodyReadState.COMPLETE)
            capture.markStarted()
            assertThat(capture.readState).isEqualTo(BodyReadState.COMPLETE)
        }

        @Test
        fun `should ignore marks once frozen so the emitted state is a consistent snapshot`() {
            // What is tested: the read state follows the freeze contract of every other mutation.
            // Success criteria: a completion signal arriving after freeze leaves the state at PARTIAL.
            // Why it matters: the emitter freezes first and reads second; a mark slipping in between
            //   would make the counter disagree with the body text and size logged for the same exchange.
            // Given: a started capture, frozen by the emission
            val capture = BoundedBodyCapture(8)
            capture.markStarted()
            capture.freeze()

            // When: a late completion arrives
            capture.markCompleted()

            // Then: the snapshot is unchanged
            assertThat(capture.readState).isEqualTo(BodyReadState.PARTIAL)
        }
    }
}

/** A publisher that ignores cancellation - the Reactive-Streams-permitted late onNext, made deterministic. */
private class ManualPublisher : Publisher<DataBuffer> {
    private lateinit var subscriber: Subscriber<in DataBuffer>

    override fun subscribe(s: Subscriber<in DataBuffer>) {
        subscriber = s
        s.onSubscribe(
            object : Subscription {
                override fun request(n: Long) = Unit

                override fun cancel() = Unit
            },
        )
    }

    fun emit(buffer: DataBuffer) = subscriber.onNext(buffer)
}
