package eu.inqudium.legatium.webclient.logging

import org.reactivestreams.Subscription
import org.springframework.web.reactive.function.client.ClientResponse
import reactor.core.CoreSubscriber
import reactor.core.publisher.Mono
import reactor.core.publisher.MonoOperator
import reactor.util.context.Context

/**
 * The response `Mono` as the caller receives it: the response recorded on the exchange and its body
 * wrapped ([onResponse]) on the way through, and the OWNERSHIP of the exchange's completion handed from
 * this Mono to the body only once the downstream has actually taken the response.
 *
 * ## Why an operator of its own, not `map`/`doOnCancel`/`doFinally`
 *
 * The exchange completes at the body's terminal signal once a response was delivered, and at this
 * Mono's own error or cancel signal otherwise. The handover between the two owners is a RACE when a
 * cancel arrives from another thread while the response is being handed on: with `map` setting the
 * state before Reactor's `FluxMap` calls the downstream, a cancel in that window was ignored as "the
 * body owns it" - but a downstream that is cancelling drops the value it is handed and never subscribes
 * to the body, so neither owner completed the exchange: no event, the open-exchanges gauge one too high
 * forever. The peek operators cannot see when the downstream has RETURNED from `onNext`; a subscriber of
 * our own can.
 *
 * The state therefore moves `OPEN -> DELIVERING` before `actual.onNext` and `DELIVERING -> RESPONDED`
 * after it returned. A cancel that reaches this operator in `OPEN` or `DELIVERING` from any OTHER thread
 * than the delivering one is the caller walking away - possibly while the downstream is about to drop the
 * response - and completes the exchange as `cancelled` right here (the body, if it is ever subscribed to,
 * finds the exchange completed and stays silent). A cancel from WITHIN the delivery on the same thread is
 * the downstream taking the response and cutting the Mono short as part of that (`Flux.next()` cancels
 * upstream before it hands the value on): the body owns the exchange from here, exactly as after a
 * normal `onComplete`. The thread identity is the same distinction [ObservedBody] draws for the body.
 * Pinned by the filter's unit tests with a `next()` and a barrier-driven concurrent cancel.
 */
internal class ObservedResponse(
    source: Mono<ClientResponse>,
    private val exchange: Exchange,
    /** Records the response on the exchange and returns it with the body wrapped; pure assembly. */
    private val onResponse: (Exchange, ClientResponse) -> ClientResponse,
    /** A cancel by the caller before the body owns the exchange: completes it as `cancelled` unless the body already owns it. */
    private val onCancelled: (Exchange) -> Unit,
    /** The Mono's own terminal end of an exchange without a delivered response: the filter's exactly-once `complete`. */
    private val onTerminal: (Exchange) -> Unit,
) : MonoOperator<ClientResponse, ClientResponse>(source) {
    override fun subscribe(actual: CoreSubscriber<in ClientResponse>) {
        source.subscribe(Handover(actual))
    }

    private inner class Handover(
        private val actual: CoreSubscriber<in ClientResponse>,
    ) : CoreSubscriber<ClientResponse>,
        Subscription {
        private lateinit var upstream: Subscription

        /** The thread inside [actual]'s `onNext` right now, null outside the delivery. */
        @Volatile
        private var deliveringOn: Thread? = null

        override fun currentContext(): Context = actual.currentContext()

        override fun onSubscribe(s: Subscription) {
            upstream = s
            actual.onSubscribe(this)
        }

        override fun request(n: Long) = upstream.request(n)

        override fun cancel() {
            upstream.cancel()
            if (deliveringOn !== Thread.currentThread()) {
                onCancelled(exchange)
            }
        }

        override fun onNext(response: ClientResponse) {
            if (!exchange.state.compareAndSet(ExchangeState.OPEN, ExchangeState.DELIVERING)) {
                // The exchange is already over (a cancel won the race): the response passes through
                // unobserved - the caller's pipeline may still want it, the event is written.
                actual.onNext(response)
                return
            }
            val observed = onResponse(exchange, response)
            deliveringOn = Thread.currentThread()
            try {
                actual.onNext(observed)
            } finally {
                // The downstream returned: from here the body owns the completion - unless a concurrent
                // cancel completed the exchange meanwhile, in which case the CAS fails and nothing changes.
                exchange.state.compareAndSet(ExchangeState.DELIVERING, ExchangeState.RESPONDED)
                deliveringOn = null
            }
        }

        override fun onError(t: Throwable) {
            exchange.failure = t
            actual.onError(t)
            onTerminal(exchange)
        }

        override fun onComplete() {
            if (exchange.state.get() == ExchangeState.OPEN) {
                // An EMPTY completion - a broken connector, a host filter that swallowed an error into
                // Mono.empty() - is a failure: WebClient raises exactly this for the caller.
                exchange.failure = IllegalStateException(ClientRequestLoggingFilter.NO_RESPONSE_MESSAGE)
            }
            actual.onComplete()
            if (exchange.state.get() != ExchangeState.RESPONDED) {
                // A delivered response hands the completion to the body's terminal signal.
                onTerminal(exchange)
            }
        }
    }
}
