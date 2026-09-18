package eu.inqudium.legatium.webclient.logging

import eu.inqudium.legatium.common.TraceMdcKeys
import org.springframework.web.reactive.function.client.ClientResponse
import reactor.util.context.Context
import reactor.util.context.ContextView
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicReference

/**
 * All state one outbound exchange accumulates between filter entry and log emission: the request-side
 * coordinates captured EAGERLY at wiring time (the emission runs from a terminal callback on whatever
 * thread completes the body), the body captures, and the flags the reactive lifecycle marks along the
 * way. Mutable fields are `@Volatile`: signal callbacks and the emission can run on different threads.
 */
internal class Exchange(
    val method: String,
    /**
     * [eu.inqudium.legatium.common.RequestTarget.target] - the MDC coordinate, and the message's
     * [subject] for an unnamed client.
     */
    val target: String,
    /** [eu.inqudium.legatium.common.RequestTarget.host]. */
    val host: String?,
    /** [eu.inqudium.legatium.common.RequestTarget.path]. */
    val path: String,
    val query: String?,
    /** The exchange identity, [eu.inqudium.legatium.common.ClientIdentity.requestId] (ADR-0002). */
    val requestId: String,
    val requestHeaders: List<Pair<String, String>>,
    /**
     * The URI template the client recorded for the request
     * ([ClientRequestLoggingFilter.URI_TEMPLATE_ATTRIBUTE]); absent for an expanded URI.
     */
    val uriTemplate: String?,
    /**
     * [eu.inqudium.legatium.common.AdapterName.of] the request attribute (ADR-0009); null for a
     * client the host did not name.
     */
    val name: String?,
    val requestCapture: BoundedBodyCapture?,
    val responseCapture: BoundedBodyCapture?,
    /**
     * [eu.inqudium.legatium.common.declaredCharsetOrUtf8] of the request headers at wiring time,
     * for the logged value.
     */
    val requestCharset: Charset,
    val startNanos: Long,
    /**
     * The outgoing `traceparent`'s trace id and parent-id, published as [TraceMdcKeys] explains;
     * null without the header.
     */
    val traceId: String? = null,
    val spanId: String? = null,
    /**
     * The Reactor Context the caller subscribed with, captured at wiring (ADR-0010): immutable, so a
     * reference suffices, and the same for every thread and every resubscription of the call. The
     * emitter restores the caller's thread-locals from it around the exchange line
     * ([AmbientContextRestorer]).
     */
    val ambient: ContextView = Context.empty(),
) {
    /**
     * The lifecycle state ([ExchangeState]) - ONE atomic value instead of independent flags, so the
     * legal transitions are enumerable and the transition to [ExchangeState.COMPLETED] is won exactly
     * once; gauge-close and emission ride that single transition.
     */
    val state = AtomicReference(ExchangeState.OPEN)

    /**
     * What the arrival and completion messages name the call by: the client's [name] when the host gave
     * one, the [target] otherwise (ADR-0009). Behind a sidecar the target is the same for every
     * dependency, so a named client reads by its name in a plain-text appender; the target still rides
     * the `adapter_route` MDC entry and the `adapter_url_*` fields.
     */
    val subject: String
        get() = name ?: target

    /**
     * The failure of the call - the error signal of the response `Mono` (no response), or the error
     * signal of the body `Flux` (a response exists, its status is known).
     */
    @Volatile
    var failure: Throwable? = null

    /**
     * True when the CALLER abandoned the subscription - before the response (a downstream timeout, a
     * disposed caller) or mid-body from outside the delivery (a disconnect, a timer). A consumer that
     * cancels from within its own `onNext` because it has read enough does not set it (see [ObservedBody]).
     */
    @Volatile
    var cancelled: Boolean = false

    /**
     * The response as delivered to the caller - status and headers are read from it at emission. Null
     * when the call produced no response.
     */
    @Volatile
    var response: ClientResponse? = null
}

/**
 * The states of [Exchange.state], in order. An exchange in [RESPONDED] whose body the application
 * never subscribes to (and never releases) stays open on the gauge - the module's liveness signal -
 * rather than logging a body that was never read as complete; [DELIVERING] exists because a cancel
 * racing the handover from another thread must still find an owner ([ObservedResponse]).
 */
internal enum class ExchangeState {
    /** From wiring: the request is sent, no response yet. */
    OPEN,

    /**
     * The response is being handed to the downstream subscriber (its `onNext` has not returned) - a
     * concurrent cancel still completes the exchange itself.
     */
    DELIVERING,

    /** The downstream has taken the response; the emission waits for the body's terminal signal. */
    RESPONDED,

    /** Exactly once, by whichever terminal callback wins the transition - gauge-close and emission ride it. */
    COMPLETED,
}
