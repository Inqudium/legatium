package eu.inqudium.legatium.restclient.logging

import eu.inqudium.legatium.common.TraceMdcKeys
import org.springframework.http.HttpHeaders
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
    /** [eu.inqudium.legatium.common.RequestTarget.target] - the message and MDC coordinate. */
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
     * ([ClientRequestLoggingInterceptor.URI_TEMPLATE_ATTRIBUTE]); null for an expanded URI.
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
     * The caller's MDC at wiring, own and trace keys excluded (ADR-0011) - restored by the emitter when
     * the response is closed on another thread than the one that made the call.
     */
    val callerMdc: CallerMdcSnapshot = CallerMdcSnapshot.NONE,
) {
    /**
     * The exactly-once guard of the COMPLETION (gauge close + emission): the response close and the
     * no-response failure path can never both happen for one exchange, but a response closed twice can -
     * whoever wins this CAS completes, and the emitter trusts it (one guard, like the reactive twin's
     * state machine).
     */
    val completed = AtomicBoolean(false)

    /**
     * The failure of the call - the exception the wire call threw (no response), or the exception the
     * application met on the response it was handed: opening or reading the body, asking for status or
     * headers, closing the body stream or the response (a response exists, its status may be known).
     */
    @Volatile
    var failure: Throwable? = null

    /**
     * The REAL response the wire call produced. Null when the call produced no response. Status and
     * headers are READ at handover ([responseStatus], [responseHeaders]) - they are final there, and
     * the emission runs after the client closed the response, when an engine need not answer.
     */
    @Volatile
    var response: ClientHttpResponse? = null

    /** The status code read at handover; null when the call produced no response or the engine could not say. */
    @Volatile
    var responseStatus: Int? = null

    /**
     * The response's own header object, obtained at handover - a live view for engines that expose one
     * (Jetty's `HttpFields`), final in content once the status line arrived; null when the call produced
     * no response or the engine could not say.
     */
    @Volatile
    var responseHeaders: HttpHeaders? = null
}
