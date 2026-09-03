package eu.inqudium.legatium.webclient.logging

import eu.inqudium.legatium.common.TraceMdcKeys
import org.springframework.web.reactive.function.client.ClientResponse
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
    /** The request TARGET, `scheme://host[:port]/path` without the query - the message and MDC coordinate. */
    val target: String,
    /** The peer host with an explicit port when the URI names one; null for a URI without an authority. */
    val host: String?,
    /** The raw request path as sent (`/` for an empty path). */
    val path: String,
    val query: String?,
    /**
     * The exchange identity (`client_request_id`, ADR-0002): the `traceparent` trace id when the outgoing
     * request carried a conformant one, otherwise the accepted or generated-and-sent correlation id.
     */
    val requestId: String,
    val requestHeaders: List<Pair<String, String>>,
    /** The URI template the client recorded for the request (`WebClient.uri(String, ...)`); null for an expanded URI. */
    val uriTemplate: String?,
    val requestCapture: BoundedBodyCapture?,
    val responseCapture: BoundedBodyCapture?,
    /** Charset of the request body for the logged value, resolved from the Content-Type at wiring time. */
    val requestCharset: Charset,
    val startNanos: Long,
    /**
     * Trace context parsed from the outgoing W3C `traceparent` header: the trace id is the client span's
     * trace id; the parent-id is the local client span the peer will see as its parent (see
     * [TraceMdcKeys]). Null without the header.
     */
    val traceId: String? = null,
    val spanId: String? = null,
) {
    /**
     * The lifecycle state - ONE atomic value instead of independent flags, so the legal transitions are
     * enumerable: `OPEN` from wiring (request sent, no response yet); `RESPONDED` once the response
     * arrived and the emission waits for the body's terminal signal; `COMPLETED` exactly once, by
     * whichever terminal callback wins the transition - gauge-close and emission ride that single
     * transition.
     */
    val state = AtomicReference(ExchangeState.OPEN)

    /**
     * The failure of the call - the error signal of the response `Mono` (no response), or the error
     * signal of the body `Flux` (a response exists, its status is known).
     */
    @Volatile
    var failure: Throwable? = null

    /** True when the subscription was cancelled - before the response (a downstream timeout) or mid-body. */
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
 * See [Exchange.state]. An exchange in [RESPONDED] whose body the application never subscribes to (and
 * never releases) stays open on the gauge - the module's liveness signal - rather than logging a body
 * that was never read as complete.
 */
internal enum class ExchangeState { OPEN, RESPONDED, COMPLETED }
