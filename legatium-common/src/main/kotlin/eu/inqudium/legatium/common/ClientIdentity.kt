package eu.inqudium.legatium.common

import org.springframework.http.HttpHeaders

/**
 * The exchange identity of one outbound call, resolved per ADR-0002 from the request headers: a
 * conformant `traceparent`'s trace id IS the request id (a correlation header the caller put on the
 * request is ignored on such calls - the distributed identity outranks the private one); only a
 * traceless call accepts the correlation header already on the request (within the
 * [CorrelationHeader] rule) or generates a fresh id, and only a traceless call without an acceptable
 * one gets the header SENT - a traced call goes out observationally untouched.
 *
 * [source] is what the correlation counter records; `generatedEarlier` (a parameter of [resolve]) lets a re-entry by a retrying
 * outer interceptor - which finds the id this module generated on attempt 1 already on the request -
 * keep counting as `generated`.
 */
internal class ClientIdentity(
    val requestId: String,
    val source: RequestIdSource,
    val traceId: String?,
    val spanId: String?,
    /** True when the twin must put [requestId] on the wire itself. */
    val sendCorrelationHeader: Boolean,
) {
    companion object {
        fun resolve(
            headers: HttpHeaders,
            properties: ClientLoggingProperties,
            correlationIds: CorrelationIdGenerator,
            generatedEarlier: String? = null,
        ): ClientIdentity {
            val trace = Traceparent.parse(headers.getFirst(Traceparent.HEADER))
            val headerCorrelationId =
                if (trace == null) CorrelationHeader.accept(headers.getFirst(properties.correlationIdHeader)) else null
            val requestId = trace?.first ?: headerCorrelationId ?: correlationIds.nextCorrelationId()
            val source =
                when {
                    trace != null -> RequestIdSource.TRACE
                    headerCorrelationId != null && headerCorrelationId != generatedEarlier -> RequestIdSource.HEADER
                    else -> RequestIdSource.GENERATED
                }
            return ClientIdentity(
                requestId = requestId,
                source = source,
                traceId = trace?.first,
                spanId = trace?.second,
                sendCorrelationHeader = trace == null && headerCorrelationId == null,
            )
        }
    }
}
