package eu.inqudium.legatium.restclient.logging

import eu.inqudium.legatium.common.TraceMdcKeys
import org.springframework.http.client.ClientHttpResponse
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean

/**
 * All state one outbound exchange accumulates between interceptor entry and log emission at response
 * close: the request-side coordinates captured EAGERLY at wiring time (the emission runs from the
 * response's `close`, possibly on another thread than the one that sent the request), the body captures,
 * and what the wire call produced. Fields written after construction are `@Volatile`: the call, the body
 * read and the close can happen on different threads when a host hands the response around.
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
    /** The URI template the client recorded for the request (`RestClient.uri(String, ...)`); null for an expanded URI. */
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
    /** The exactly-once guard of the emission; whoever wins the CAS emits. */
    val logged = AtomicBoolean(false)

    /**
     * The exactly-once guard of the COMPLETION bookkeeping (gauge close + emission trigger): the
     * response close and the no-response failure path can never both happen for one exchange, but a
     * response closed twice can - whoever wins this CAS completes. [logged] stays the emitter's own
     * inner backstop.
     */
    val completed = AtomicBoolean(false)

    /**
     * The failure of the call - the exception the wire call threw (no response), or an [java.io.IOException]
     * the application met while reading the body (a response exists, its status is known).
     */
    @Volatile
    var failure: Throwable? = null

    /**
     * The REAL response the wire call produced - status and headers are read from it at emission, so
     * they are the response's final word. Null when the call produced no response.
     */
    @Volatile
    var response: ClientHttpResponse? = null
}
