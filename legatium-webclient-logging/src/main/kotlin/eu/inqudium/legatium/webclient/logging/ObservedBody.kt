package eu.inqudium.legatium.webclient.logging

import org.reactivestreams.Subscription
import org.springframework.core.io.buffer.DataBuffer
import reactor.core.CoreSubscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxOperator
import reactor.core.publisher.Operators
import reactor.util.context.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The response body as the caller receives it: every buffer teed into [capture] (when one exists) on
 * its way through, the read state marked, and the body's TERMINAL signal - complete, error, or cancel -
 * turned into the exchange's completion ([onTerminal], the filter's exactly-once `complete`).
 *
 * ## Why an operator of its own, not `doOnCancel`/`doFinally`
 *
 * Two very different things reach a body publisher as a CANCEL signal, and only the subscriber that sees
 * the cancel can tell them apart:
 *
 * - **The consumer decided it has read enough** - and says so from WITHIN the delivery of a buffer to
 *   it: Spring's own body skip (`bodyToMono(Void.class)`, `toEntity(Void.class)`, an unsupported media
 *   type) drains a `ClientHttpResponse` through `takeWhile(release; false)`, which cancels upstream in
 *   `onNext` of the FIRST buffer; a `take(n)` cancels in `onNext` of the n-th. The exchange is over,
 *   the peer answered, the application chose not to read the rest: the outcome is `success`, and the
 *   read state stays `partial` (the size counter shows what was consumed). Logging these as
 *   `cancelled` would flag every fire-and-forget call at WARN and, in `on-failure` body mode, write both
 *   bodies of a healthy call.
 * - **The caller walked away** - from anywhere else: a `timeout()` operator's timer thread, a disposed
 *   `Disposable`, a client that disconnected from a server streaming this body through. That is the
 *   reactive reality a blocking call cannot have, and it is what `adapter_outcome=cancelled` reports.
 *
 * The distinction is the identity of the cancelling thread against the thread currently inside
 * `actual.onNext`: a cancel from within the delivery is the consumer's own decision, a cancel from any
 * other thread - or from this thread outside a delivery - is an abandonment. Pinned by the filter's
 * unit tests with Spring's skip, a `take`, and an out-of-band cancel.
 */
internal class ObservedBody(
    source: Flux<DataBuffer>,
    private val exchange: Exchange,
    private val capture: BoundedBodyCapture?,
    private val onTerminal: (Exchange) -> Unit,
    private val onTeeFailure: (Exception) -> Unit,
) : FluxOperator<DataBuffer, DataBuffer>(source) {
    override fun subscribe(actual: CoreSubscriber<in DataBuffer>) {
        capture?.markStarted()
        source.subscribe(Observer(actual))
    }

    private inner class Observer(
        private val actual: CoreSubscriber<in DataBuffer>,
    ) : CoreSubscriber<DataBuffer>,
        Subscription {
        private lateinit var upstream: Subscription

        /** The thread inside [actual]'s `onNext` right now, null outside a delivery. */
        @Volatile
        private var deliveringOn: Thread? = null

        private val ended = AtomicBoolean(false)

        override fun currentContext(): Context = actual.currentContext()

        override fun onSubscribe(s: Subscription) {
            upstream = s
            actual.onSubscribe(this)
        }

        override fun request(n: Long) = upstream.request(n)

        override fun cancel() {
            upstream.cancel()
            if (ended.compareAndSet(false, true)) {
                if (deliveringOn !== Thread.currentThread()) {
                    exchange.cancelled = true
                }
                onTerminal(exchange)
            }
        }

        override fun onNext(buffer: DataBuffer) {
            // The tee is a passive copy and fail-open: a tee that throws costs the capture, never the
            // buffer - the caller's original continues downstream untouched.
            val observed =
                if (capture == null) {
                    buffer
                } else {
                    try {
                        tee(capture, buffer)
                    } catch (e: Exception) {
                        onTeeFailure(e)
                        buffer
                    }
                }
            deliveringOn = Thread.currentThread()
            try {
                actual.onNext(observed)
            } finally {
                deliveringOn = null
            }
        }

        override fun onError(t: Throwable) {
            if (ended.compareAndSet(false, true)) {
                exchange.failure = t
                actual.onError(t)
                onTerminal(exchange)
            } else {
                Operators.onErrorDropped(t, currentContext())
            }
        }

        override fun onComplete() {
            if (ended.compareAndSet(false, true)) {
                capture?.markCompleted()
                actual.onComplete()
                onTerminal(exchange)
            }
        }
    }
}
