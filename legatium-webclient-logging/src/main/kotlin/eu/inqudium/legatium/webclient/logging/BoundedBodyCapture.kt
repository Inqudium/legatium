package eu.inqudium.legatium.webclient.logging

import eu.inqudium.legatium.common.BodyReadState
import eu.inqudium.legatium.common.BoundedByteBuffer
import java.nio.charset.Charset
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A bounded tee target: the capturing decorators copy every body byte that actually flows through the
 * exchange into this buffer, up to [maxBytes]; beyond the cap bytes are only counted.
 *
 * The capture is a passive copy of the live stream - it never buffers, replays, or withholds bytes - so
 * unlike a replaying body cache there is no lifecycle to manage: at the moment the exchange line is
 * written, whatever has flowed is what gets logged.
 *
 * ## Concurrency model - frozen at emission
 *
 * The reactive stack does NOT guarantee that body delivery has ended when the exchange is emitted: a
 * CANCEL (a downstream `take`, a timeout operator) completes the exchange at once ([ObservedBody]),
 * while Reactive Streams still permits an already-requested `onNext` to arrive on another thread
 * afterwards. The capture
 * therefore guards itself instead of relying on a single-writer assumption: every mutation and every
 * read runs under one uncontended [ReentrantLock], and the emitter calls [freeze] FIRST - from then on
 * the capture is immutable, a late tee call is a no-op, and the logged body and the size sample are one
 * consistent snapshot instead of a moving target.
 *
 * With `maxBytes = 0` the capture runs in COUNT-ONLY mode: nothing is buffered, [totalBytes] still
 * counts every byte - the mode the body-size metrics use when body logging is off; a negative limit is
 * rejected at construction. The tee is fed from mapped `DataBuffer`s ([tee]).
 *
 * The bytes live in the shared [BoundedByteBuffer], sized once by the length the tee learned from
 * `Content-Length` through [expectBytes]; this class adds the count, the read state and the reactive
 * stack's concurrency model.
 *
 * Besides the bytes, the response capture records HOW FAR the application consumed the body
 * ([readState]): the tee mirrors consumption, not transmission, so a response body the application
 * never subscribed to - or cancelled half-way - is invisible in the byte count alone. The response tee
 * marks the subscription and the completion signal; the emitter turns the state into the
 * `adapter.response.body.read` counter. Like every other mutation, the marks are no-ops once frozen: the
 * state is part of the emission snapshot.
 */
internal class BoundedBodyCapture(
    maxBytes: Int,
) {
    private val lock = ReentrantLock()
    private val buffer = BoundedByteBuffer(maxBytes)
    private var total: Long = 0
    private var frozen = false
    private var state = BodyReadState.UNREAD

    /** How far the application consumed the body - see [BodyReadState]. */
    val readState: BodyReadState
        get() = lock.withLock { state }

    /** The application subscribed to the body: from now on it counts as (at least) partially read. */
    fun markStarted() =
        lock.withLock {
            if (!frozen && state == BodyReadState.UNREAD) {
                state = BodyReadState.PARTIAL
            }
        }

    /** The body publisher completed: the application consumed the body to its end. */
    fun markCompleted() =
        lock.withLock {
            if (!frozen) {
                state = BodyReadState.COMPLETE
            }
        }

    /** Every byte that flowed, including those beyond the capture limit - the size metrics' source. */
    val totalBytes: Long
        get() = lock.withLock { total }

    /**
     * The body length the peer or the caller declared, as the buffer's SIZING hint
     * ([BoundedByteBuffer.expect]): ignored once a byte is buffered and once frozen. A wrong hint costs
     * allocation, never bytes - the cap and the count are unaffected.
     */
    fun expectBytes(length: Long) =
        lock.withLock {
            if (!frozen) {
                buffer.expect(length)
            }
        }

    /** The declared length the buffer is sized by, [UNKNOWN_LENGTH] without one - exposed for the tests. */
    internal val expectedBytes: Long
        get() = lock.withLock { buffer.expectedBytes }

    /**
     * Buffers the prefix of a chunk that [count] has ALREADY counted - the tee counts a chunk in full
     * before it copies, so a copy that throws costs the logged text of that chunk, never its size. Up to
     * [remainingCapacity] bytes are kept; a no-op once frozen.
     */
    fun store(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        lock.withLock {
            if (!frozen) {
                buffer.write(bytes, offset, length)
            }
        }
    }

    /**
     * Bytes the buffer can still take before [maxBytes]; 0 in count-only mode, once the cap is reached,
     * or once frozen. The reactive tee sizes its bounded prefix copy from this - the reason the tee's
     * transient allocation is bounded by the configured cap instead of the buffer size.
     */
    fun remainingCapacity(): Int = lock.withLock { if (frozen) 0 else buffer.remaining }

    /**
     * Counts [length] bytes that flowed: the reactive tee's FIRST call per chunk, for the whole chunk,
     * before it copies the prefix [store] keeps. Counting cannot throw, the copy can.
     */
    fun count(length: Int) = count(length.toLong())

    /** [count] for a byte count that never passed through user space (a zero-copy file transfer). */
    fun count(length: Long) =
        lock.withLock {
            if (!frozen) {
                total += length
            }
        }

    /**
     * Makes the capture immutable: the emission's first step. Every later [store]/[count] is a no-op,
     * so a body chunk still flowing through the tee after cancellation can neither corrupt the logged
     * text nor make the size sample disagree with it. Idempotent.
     */
    fun freeze() =
        lock.withLock {
            frozen = true
        }

    /** Whether [freeze] has been called - exposed for `BoundedBodyCaptureTest`. */
    val isFrozen: Boolean
        get() = lock.withLock { frozen }

    /**
     * The captured bytes decoded with [charset], suffixed with a truncation note when the body was larger
     * than the capture limit. Returns `null` for a body of zero bytes, so the log emission can omit the
     * key entirely instead of logging an empty string.
     */
    fun loggedValue(charset: Charset): String? =
        lock.withLock {
            buffer.render(charset, total)
        }

    companion object {
        /** No trustworthy declared length: the buffer is sized by what flows. */
        const val UNKNOWN_LENGTH = BoundedByteBuffer.UNKNOWN_LENGTH
    }
}
