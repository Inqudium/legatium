package eu.inqudium.legatium.common

import org.slf4j.MDC

/**
 * MDC keys the twins maintain around an outbound call. The values carry the module's `client_` prefix,
 * so an encoder that emits MDC entries as fields lands them in the same namespace as the
 * [ClientLogField] family both twins share - and beside, never instead of, whatever identity the
 * AMBIENT MDC already carries (an inbound request's `endpoint_request_id` from the sibling project
 * limesium, a tracing bridge's keys): the scope is an additive overlay. [ROUTE] carries the request
 * TARGET (`scheme://host[:port]/path`, query excluded): for an outbound call the host is as much part
 * of the route as the path.
 */
object MdcKeys {
    const val REQUEST_ID = "client_request_id"
    const val REQUEST_METHOD = "client_method"
    const val ROUTE = "client_route"
}

/**
 * Trace context keys of the exchange event, parsed from the OUTGOING W3C `traceparent` header (ADR-0002:
 * both twins source the trace id from the header the host's propagation put on the request, never from a
 * tracing bridge's API). [TRACE_ID] is Boot's logging-correlation key: the header's trace id IS the trace
 * the client span runs under, so the join holds. The header's parent-id is the span the PEER will see as
 * its parent - which is exactly the local client span of this call - so it is published under Boot's
 * local-span key [SPAN_ID]. (This is the mirror image of the inbound side, where the header's parent-id
 * is the CALLER's span and must not be published as the local one.) Absent header means not logged.
 */
internal object TraceMdcKeys {
    const val TRACE_ID = "traceId"
    const val SPAN_ID = "spanId"
}

/**
 * Puts the exchange identity - and, when parsed, the trace context - into the MDC and restores the
 * PREVIOUS values on close: threads are pooled, and the ambient MDC (a server-side request scope, a
 * bridge) may own the same keys. The RestClient twin opens it around the wire call (there without the
 * trace overlay: a tracing bridge's own scope is active during the call and is authoritative there) and
 * both twins open it around the emission; with [ownsTraceKeys] the scope is the ONLY authority on the
 * trace keys - a parsed id is installed, an unparsed one is REMOVED for the scope's lifetime, so a
 * stale bridge id on the emitting thread cannot be attached to the event. Either way every touched key
 * is restored on close.
 */
internal class MdcScope(
    requestId: String,
    method: String,
    route: String,
    traceId: String? = null,
    spanId: String? = null,
    ownsTraceKeys: Boolean = false,
) : AutoCloseable {
    private val applied: Map<String, String> =
        buildMap {
            put(MdcKeys.REQUEST_ID, requestId)
            put(MdcKeys.REQUEST_METHOD, method)
            put(MdcKeys.ROUTE, route)
            traceId?.let { put(TraceMdcKeys.TRACE_ID, it) }
            spanId?.let { put(TraceMdcKeys.SPAN_ID, it) }
        }

    /** The trace keys the scope suppresses: owned but not parsed. */
    private val removed: Set<String> =
        if (ownsTraceKeys) {
            setOf(TraceMdcKeys.TRACE_ID, TraceMdcKeys.SPAN_ID) - applied.keys
        } else {
            emptySet()
        }

    private val previous: Map<String, String?> = (applied.keys + removed).associateWith { MDC.get(it) }

    init {
        try {
            applied.forEach { (key, value) -> MDC.put(key, value) }
            removed.forEach { MDC.remove(it) }
        } catch (e: Exception) {
            // Roll back a PARTIAL install before propagating: a broken MDC adapter failing mid-put must
            // not leave half an identity on a pooled thread.
            try {
                close()
            } catch (rollback: Exception) {
                e.addSuppressed(rollback)
            }
            throw e
        }
    }

    /**
     * Restores every key BEST-EFFORT: one failing adapter call must not leave the remaining module-owned
     * entries on a pooled thread. The first failure is rethrown after the loop, later ones attached as
     * suppressed; the partial-install rollback above attaches a restoration failure to the ORIGINAL
     * install exception instead of replacing it.
     */
    override fun close() {
        var failure: Exception? = null
        previous.forEach { (key, value) ->
            try {
                if (value == null) MDC.remove(key) else MDC.put(key, value)
            } catch (e: Exception) {
                val first = failure
                if (first == null) failure = e else first.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }
}
