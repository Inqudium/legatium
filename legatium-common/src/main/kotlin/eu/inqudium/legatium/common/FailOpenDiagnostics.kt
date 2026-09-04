package eu.inqudium.legatium.common

/**
 * Runs the diagnostics of a fail-open catch handler - the fail-open counter increment and the internal
 * log line - so that a failure of the DIAGNOSTICS channel itself can never escape into the call.
 *
 * Every catch block in the twins reports through a Micrometer counter and an SLF4J logger; both run
 * against host-provided components (a throwing `Counter` implementation, a global throwing
 * appender/TurboFilter that also covers the internal logger). Unguarded, such a throw would leave the
 * catch handler and fail the outbound call or corrupt the response the application is reading - the one
 * outcome the fail-open contract forbids. There is nothing left to report to when the reporting channel
 * is broken, so the secondary failure is deliberately dropped.
 */
internal inline fun reportQuietly(report: () -> Unit) {
    try {
        report()
    } catch (ignored: Exception) {
        // The diagnostics channel is itself broken; the original failure was already contained.
    }
}

/**
 * The FULLY-CONFINING fail-open guard shape the emitters and callbacks share: [operation] runs; an
 * [InterruptedException] first restores the thread's interrupt flag (the JVM cleared it when it threw,
 * and on a request-serving or event-loop thread the interrupt must still reach its addressee), then -
 * like every other [Exception] - the failure goes to its handler, itself wrapped in [reportQuietly] so
 * a broken diagnostics channel cannot escape either. Nothing is rethrown and nothing runs after a
 * failure.
 *
 * Deliberately NOT used by guards with richer semantics - a rethrow of the original exception (the
 * interceptor's and filter's call-through), a produced value (fail-open wiring), or work that must still
 * happen after a confined failure (the terminal handling completes the exchange) - those keep their
 * explicit try/catch, where the deviation is visible.
 *
 * ## The boundary is `Exception`, not `Throwable` - a decision
 *
 * Every fail-open guard in the twins confines [Exception] and lets an [Error] propagate: a
 * `VirtualMachineError`, a `LinkageError` from a broken logging backend or a `StackOverflowError` is a
 * JVM-level condition no logging library can meaningfully absorb, and swallowing it would hide a
 * process that is already failing. The one thing the twins DO protect against an `Error` is their own
 * bookkeeping: a wire call that dies with an `Error` still closes the open-exchange gauge (without an
 * emission attempt), so the liveness signal cannot drift over something the module never caused.
 */
internal inline fun failOpen(
    onInterrupted: (InterruptedException) -> Unit,
    onFailure: (Exception) -> Unit,
    operation: () -> Unit,
) {
    try {
        operation()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        reportQuietly { onInterrupted(e) }
    } catch (e: Exception) {
        reportQuietly { onFailure(e) }
    }
}
