package eu.inqudium.legatium.common

/**
 * The acceptance rule for a correlation id a TRACELESS request already carries in the configured header
 * (ADR-0002, amendment of 2026-09-04). The value typically originates outside the application - an
 * inbound request propagated onto the outbound call - and lands verbatim in the message, the MDC and,
 * when selected, the header field of every line of the call, so it is bounded the way the URI is:
 *
 * - at most [MAX_LENGTH] characters;
 * - visible ASCII only (`0x21`..`0x7E`) - no whitespace, no control characters (a CR/LF would forge lines
 *   in every plain-text sink), no non-ASCII.
 *
 * A value outside the rule is treated as ABSENT: the twin generates its own id and SENDS it, replacing
 * the unacceptable value on the wire, and the `adapter.logging.correlation.id` counter records the call as
 * `generated`. Shared by both twins (ADR-0003); the sibling project limesium mirrors the rule on the
 * inbound side so the pair stays consistent.
 */
internal object CorrelationHeader {
    const val MAX_LENGTH = 200

    /** [value] when it satisfies the rule, null when absent or unacceptable. */
    fun accept(value: String?): String? {
        if (value == null || value.isEmpty() || value.length > MAX_LENGTH) {
            return null
        }
        return if (value.all { it in '\u0021'..'\u007e' }) value else null
    }
}
