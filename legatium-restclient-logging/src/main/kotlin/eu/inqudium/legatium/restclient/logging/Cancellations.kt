package eu.inqudium.legatium.restclient.logging

import eu.inqudium.legatium.common.CauseGraph

/**
 * Recognises a failed blocking call as one the CALLER abandoned (ADR-0015): its budget expired and the
 * worker was cancelled with `Future.cancel(true)`, or the calling thread was interrupted. The JDK then
 * closes the worker's blocking socket ("Closed by interrupt"), or the engine stops waiting on its own
 * future and rethrows the interrupt wrapped in an `IOException` - neither says anything about the peer.
 *
 * The primary signal is not here: it is the worker's interrupt FLAG, read where the failure is recorded
 * ([Exchange.recordFailure]) - the JDK leaves it set when it closes a socket on interrupt, Spring's JDK
 * and Jetty request factories and Apache's pool lease restore it after catching the interrupt. This
 * object is the FALLBACK for a flag something between the engine and this interceptor already consumed:
 * the CAUSE chain (and suppressed exceptions, recursively) - the [CauseGraph] walk
 * [eu.inqudium.legatium.common.Timeouts] uses, so both classifications see the same throwables - matched
 * by class NAME so the twin needs no engine dependency:
 *
 * - `java.lang.InterruptedException` and `java.nio.channels.ClosedByInterruptException`, as types: both
 *   mean an interrupt and nothing else.
 * - `java.net.SocketException` with the message `Closed by interrupt` - the JDK's socket closed on an
 *   interrupt (`NioSocketImpl`). Fallback: a message is no contract, and a JDK that rewords it loses
 *   only this fallback, not the flag.
 * - Apache HttpComponents 5's `org.apache.hc.client5.http.impl.classic.RequestFailedException` with the
 *   message `Request aborted` - the classic client's interrupted pool lease and aborted execution.
 *   Fallback as well, and deliberately not the class alone: the same class carries
 *   `Request execution failed` for a lease that FAILED, which stays a `failure`.
 *
 * Not `java.io.InterruptedIOException` as a whole: `SocketTimeoutException` and Apache's
 * `ConnectionRequestTimeoutException` are ones too, and they are timeouts.
 */
internal object Cancellations {
    private val CANCELLATION_CLASS_NAMES =
        setOf(
            "java.lang.InterruptedException",
            "java.nio.channels.ClosedByInterruptException",
        )

    /** Class name to the one message under which that class reports a cancellation. */
    private val CANCELLATION_MESSAGES =
        mapOf(
            "java.net.SocketException" to "Closed by interrupt",
            "org.apache.hc.client5.http.impl.classic.RequestFailedException" to "Request aborted",
        )

    /**
     * True when any throwable of [throwable]'s cause graph (the [CauseGraph] walk: causes, suppressed
     * exceptions, cycles) reports a cancellation as defined on this object. Null is never one.
     */
    fun isCancellation(throwable: Throwable?): Boolean = CauseGraph.any(throwable) { reportsCancellation(it.javaClass, it.message) }

    /** The per-link rule: a cancellation type, or a class that reports a cancellation under [message]. */
    private fun reportsCancellation(
        type: Class<*>,
        message: String?,
    ): Boolean =
        CauseGraph.hasTypeNamed(type, CANCELLATION_CLASS_NAMES) ||
            (message != null && CauseGraph.hasType(type) { CANCELLATION_MESSAGES[it.name] == message })
}
