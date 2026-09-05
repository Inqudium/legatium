package eu.inqudium.legatium.webclient.logging

import eu.inqudium.legatium.common.BodyReadState
import eu.inqudium.legatium.common.decodeTruncated
import java.io.ByteArrayOutputStream
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
 * CANCEL (a downstream `take`, a timeout operator) runs `doFinally` immediately, while Reactive Streams
 * still permits an already-requested `onNext` to arrive on another thread afterwards. The capture
 * therefore guards itself instead of relying on a single-writer assumption: every mutation and every
 * read runs under one uncontended [ReentrantLock], and the emitter calls [freeze] FIRST - from then on
 * the capture is immutable, a late tee call is a no-op, and the logged body and the size sample are one
 * consistent snapshot instead of a moving target.
 *
 * With `maxBytes = 0` the capture runs in COUNT-ONLY mode: nothing is buffered, [totalBytes] still
 * counts every byte - the mode the body-size metrics use when body logging is off. The tee is fed from
 * mapped `DataBuffer`s ([tee]).
 *
 * Besides the bytes, the response capture records HOW FAR the application consumed the body
 * ([readState]): the tee mirrors consumption, not transmission, so a response body the application
 * never subscribed to - or cancelled half-way - is invisible in the byte count alone. The response tee
 * marks the subscription and the completion signal; the emitter turns the state into the
 * `adapter.response.body.read` counter. Like every other mutation, the marks are no-ops once frozen: the
 * state is part of the emission snapshot.
 */
internal class BoundedBodyCapture(
    private val maxBytes: Int,
) {
    private val lock = ReentrantLock()
    private val buffer = ByteArrayOutputStream()
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

    fun capture(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        lock.withLock {
            if (frozen) {
                return
            }
            val room = maxBytes - buffer.size()
            if (room > 0) {
                buffer.write(bytes, offset, minOf(length, room))
            }
            total += length
        }
    }

    /**
     * Bytes the buffer can still take before [maxBytes]; 0 in count-only mode, once the cap is reached,
     * or once frozen. The reactive tee sizes its bounded prefix copy from this - the reason the tee's
     * transient allocation is bounded by the configured cap instead of the buffer size.
     */
    fun remainingCapacity(): Int = lock.withLock { if (frozen) 0 else maxBytes - buffer.size() }

    /**
     * Counts [length] bytes that flowed WITHOUT buffering them: the reactive tee's path for everything
     * beyond [remainingCapacity], and its whole path in count-only mode.
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
     * Makes the capture immutable: the emission's first step. Every later [capture]/[count] is a no-op,
     * so a body chunk still flowing through the tee after cancellation can neither corrupt the logged
     * text nor make the size sample disagree with it. Idempotent.
     */
    fun freeze() =
        lock.withLock {
            frozen = true
        }

    /** Whether [freeze] has been called - exposed for the tee tests. */
    val isFrozen: Boolean
        get() = lock.withLock { frozen }

    /**
     * The captured bytes decoded with [charset], suffixed with a truncation note when the body was larger
     * than the capture limit. Returns `null` for a body of zero bytes, so the log emission can omit the
     * key entirely instead of logging an empty string.
     */
    fun loggedValue(charset: Charset): String? =
        lock.withLock {
            if (total == 0L) {
                return null
            }
            if (total > buffer.size()) {
                "${decodeTruncated(buffer.toByteArray(), charset)}... [truncated, $total bytes total]"
            } else {
                buffer.toString(charset)
            }
        }
}
