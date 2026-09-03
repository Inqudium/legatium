package eu.inqudium.legatium.common

/**
 * Minimal W3C `traceparent` parsing (`version-traceid-parentid-flags`). On the OUTBOUND side the header
 * carries the context the host's propagation wrote onto the request: the trace id is the trace the
 * client span runs under (that is what makes the log-to-trace join work), and the parent-id is the span
 * the peer will treat as its parent - the local client span of this call, published as `spanId` (see
 * [TraceMdcKeys]).
 *
 * Validity follows the W3C Trace Context Recommendation in full:
 * both ids are lowercase hexadecimal of fixed length and neither may be all zeros; the version is two
 * lowercase-hex characters and `ff` is forbidden; the flags are two lowercase-hex characters. Version
 * `00` is exactly four fields; a higher version is parsed by the version-00 rules for its first four
 * fields and may carry additional fields, as the specification prescribes for forward compatibility.
 * Conformance is pinned by the `traceparent/conformance.txt` fixture the tests read.
 */
internal object Traceparent {
    const val HEADER = "traceparent"

    private val VERSION = Regex("[0-9a-f]{2}")
    private val TRACE_ID = Regex("[0-9a-f]{32}")
    private val SPAN_ID = Regex("[0-9a-f]{16}")
    private val FLAGS = Regex("[0-9a-f]{2}")
    private const val INVALID_VERSION = "ff"
    private const val CURRENT_VERSION = "00"

    /**
     * Extracts `(traceId, spanId)` or null when the value is absent or not a conformant `traceparent`
     * (malformed structure, invalid version or flags, non-lowercase-hex or all-zero ids).
     */
    fun parse(value: String?): Pair<String, String>? {
        val parts = (value ?: return null).split('-')
        if (parts.size < 4) {
            return null
        }
        val version = parts[0]
        if (!VERSION.matches(version) || version == INVALID_VERSION) {
            return null
        }
        if (version == CURRENT_VERSION && parts.size != 4) {
            return null
        }
        val traceId = parts[1]
        val spanId = parts[2]
        if (!TRACE_ID.matches(traceId) || !SPAN_ID.matches(spanId) || !FLAGS.matches(parts[3])) {
            return null
        }
        if (traceId.all { it == '0' } || spanId.all { it == '0' }) {
            return null
        }
        return traceId to spanId
    }
}
