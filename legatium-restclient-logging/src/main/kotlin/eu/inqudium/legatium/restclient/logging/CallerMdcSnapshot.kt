package eu.inqudium.legatium.restclient.logging

import eu.inqudium.legatium.common.MdcKeys
import eu.inqudium.legatium.common.NoOpScope
import eu.inqudium.legatium.common.TraceMdcKeys
import eu.inqudium.legatium.common.installMdcEntries
import eu.inqudium.legatium.common.restoreMdcEntries
import org.slf4j.MDC

/**
 * The caller's MDC as it was when the exchange was wired, for the one case in which the emission does
 * not run on the caller's thread (ADR-0011): the host handed the response to another thread and closes
 * it there - a streamed body, a pooled reader - and that thread carries none of the caller's context,
 * so the client line would not join the server line it was made from.
 *
 * Captured on the calling thread at wiring, WITHOUT the keys the module owns (`adapter_*`) and the trace
 * keys: those belong to the emission's [eu.inqudium.legatium.common.MdcScope], and a nested client call
 * would otherwise carry the outer call's identity in its snapshot. [restore] installs the entries only
 * on ANOTHER thread than the one that captured them - on the caller's own thread the MDC is simply
 * present, and a value the caller updated between the call and the close stays the newer one. Additive
 * like the emission scope: the snapshot wins for the keys it holds, everything else stays as the
 * emitting thread has it, and every touched key is restored on close.
 *
 * The RestClient counterpart of the WebClient twin's `AmbientContextRestorer`, with the thread as the
 * source instead of the Reactor Context: on the blocking stack the thread IS the caller's context.
 */
internal class CallerMdcSnapshot private constructor(
    /** The thread the snapshot was taken on - the thread on which restoring it would be a no-op. */
    private val thread: Thread?,
    /** The caller's entries, the module's own and the trace keys excluded. */
    internal val entries: Map<String, String>,
) {
    /**
     * The scope installing the entries - a no-op on the capturing thread and for an empty snapshot
     * (the class KDoc has the why).
     */
    fun restore(): AutoCloseable {
        if (entries.isEmpty() || Thread.currentThread() === thread) {
            return NoOpScope
        }
        val previous = entries.keys.associateWith { MDC.get(it) }
        // The partial-install rollback and the best-effort restore are MdcScope's rules, shared.
        installMdcEntries(entries, previous)
        return AutoCloseable { restoreMdcEntries(previous) }
    }

    companion object {
        private val OWNED_KEYS = setOf(MdcKeys.REQUEST_ID, MdcKeys.REQUEST_METHOD, MdcKeys.ROUTE, TraceMdcKeys.TRACE_ID, TraceMdcKeys.SPAN_ID)

        /** No snapshot: restores nothing, wherever it is closed. */
        val NONE: CallerMdcSnapshot = CallerMdcSnapshot(null, emptyMap())

        /**
         * The current thread's MDC, the module's own and the trace keys left out. An empty MDC costs
         * nothing: the adapter returns null and no map is built.
         */
        fun capture(): CallerMdcSnapshot {
            val entries = MDC.getCopyOfContextMap()?.filterKeys { it !in OWNED_KEYS }?.takeIf { it.isNotEmpty() } ?: return NONE
            return CallerMdcSnapshot(Thread.currentThread(), entries)
        }
    }
}
