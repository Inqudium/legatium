package eu.inqudium.legatium.common

import org.slf4j.Logger
import org.slf4j.event.Level

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
 *
 * ## The teardown rule
 *
 * The guards around a scope TEARDOWN - the interceptor's call-wide MDC scope, both emitters' emission
 * scope and caller-context scope (ADR-0010, ADR-0011) - follow one rule in both twins: once the line is
 * on the logger, a failure on the way OUT is bookkeeping, counted `stage=wiring` and reported through
 * this function - never a lost line (the emission counter does not see it), and never a masked
 * exception: the teardown runs in a `finally` with its own catch, so an emission failure or the
 * client's own exception keeps propagating past it.
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

/**
 * What a failure of stage `wiring` COST the exchange - the one choice a wiring guard makes when it
 * reports. The level of its breadcrumb and whether the stack trace goes along follow from the cost, so
 * the guards of both twins share one form and cannot drift apart in it (they had - some warned, some
 * errored, some carried the trace and some did not, with no rule behind the difference: architecture
 * review of 2026-09-21, finding 1). The sentence stays the guard's own.
 */
internal enum class WiringCost(
    /** The breadcrumb's level. */
    val level: Level,
    /** Whether the breadcrumb carries the stack trace beside the exception's `toString`. */
    val withStackTrace: Boolean,
) {
    /**
     * The call runs without a feature the guard was wiring - the logging altogether, or the identity on
     * the calling thread: ERROR, with the stack trace, because the operator has to find the cause to get
     * the feature back and nothing else will show it.
     */
    LOST_FEATURE(Level.ERROR, true),

    /**
     * The line is out, but a scope's teardown failed and the thread may keep keys that are not its own:
     * WARN - the exchange IS logged - with the stack trace, because the stale keys outlive the exchange
     * and join the thread's next lines to the wrong request.
     */
    DIRTY_TEARDOWN(Level.WARN, true),

    /**
     * The event follows, degraded - without its status, a sample, a buffer, or the caller's keys: WARN
     * with the exception's `toString` only; the event itself shows what is missing.
     */
    DEGRADED_EVENT(Level.WARN, false),
}

/**
 * The report of a `stage=wiring` guard, in ONE shape for every such guard of both twins: the fail-open
 * counter, then the breadcrumb on [log] - [message] with its [args] as SLF4J placeholders, the
 * exception's `toString` appended as the last placeholder, level and stack trace per [cost] - the whole
 * under [reportQuietly], so a broken diagnostics channel cannot escape either. The metrics owner's own
 * once-per-meter warning keeps its shape: it throttles, which no other wiring guard does.
 */
internal fun reportWiringFailure(
    metrics: ClientLoggingMetrics,
    log: Logger,
    cost: WiringCost,
    e: Exception,
    message: String,
    vararg args: Any?,
) = reportQuietly {
    metrics.wiringFailure()
    log
        .atLevel(cost.level)
        .setCauseIfPresent(e.takeIf { cost.withStackTrace })
        .log("$message: {}", *args, e.toString())
}
