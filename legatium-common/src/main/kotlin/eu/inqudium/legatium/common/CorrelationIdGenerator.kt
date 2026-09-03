package eu.inqudium.legatium.common

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * Supplies the correlation id for a TRACELESS outbound call (ADR-0002) whose request did not already
 * carry one in the configured correlation header (`ClientLoggingProperties.correlationIdHeader` in each
 * twin). The generated id is SENT to the peer on that header, so the peer can quote it.
 *
 * Injectable for the same reason as [NanoTimeSource]: randomness is ambient state, and tests must be able
 * to pin the generated id to a known value without any mocking library.
 */
fun interface CorrelationIdGenerator {
    fun nextCorrelationId(): String

    companion object {
        /**
         * The production default: a [CountingCorrelationIdGenerator] - a random per-JVM base-36
         * prefix plus a monotonically increasing counter, 21 lowercase alphanumeric characters for
         * the first 36^8 ids of an instance lifetime - roughly nine years at a sustained 10,000
         * ids per second, so an operational certainty rather than an unconditional one (NOT a
         * UUID; see that class's documentation for the rationale and the format contract).
         */
        val DEFAULT: CorrelationIdGenerator = CountingCorrelationIdGenerator()
    }
}

/**
 * Default [CorrelationIdGenerator]: a random per-instance prefix followed by a monotonically
 * increasing counter, both rendered in base 36 and both of fixed width.
 *
 * ## Why not a random UUID per call
 *
 * `UUID.randomUUID().toString()` draws 16 bytes from a process-wide, statically shared
 * [SecureRandom] on every call. On a reactive stack that is the wrong shape twice over: the
 * reseeding path of the native provider reads from a system entropy source behind a monitor,
 * which is blocking work on an event loop and a pinning point under virtual threads. This
 * generator draws randomness exactly once, at construction time, and the per-call path is a
 * single atomic increment plus a radix conversion.
 *
 * The latency difference is unlikely to be visible in a client-logging pipeline; the structural
 * argument — no shared lock, no I/O in the hot path — is what motivates this implementation.
 *
 * ## Uniqueness model
 *
 * Within one instance, uniqueness is guaranteed rather than probable: the counter never repeats.
 * Across instances it is probabilistic, and a prefix collision is worse than a UUID collision:
 * two colliding instances do not produce one duplicate id, they produce two near-identical id
 * sequences, because both counters start at zero. This is why the prefix is not narrowed below
 * 64 bits — entropy in the prefix is what bounds that failure mode. With 64 bits and 10,000
 * instance starts inside a log retention window the birthday probability is around 3e-12.
 *
 * A colliding prefix is not silently unrecoverable: log entries carry the pod name as platform
 * metadata, so the two sequences remain separable by adding an instance filter.
 *
 * ## Ordering
 *
 * Base 36 uses `[0-9a-z]`, whose ASCII code points are ordered consistently with their digit
 * values, so for equal-length strings lexicographic order equals numeric order. Combined with
 * the fixed widths below, ids from one instance therefore sort in the order the counter handed
 * them out. Note that this is the order of id *allocation*, not of log *emission* — two
 * concurrent calls can log out of id order. The id is a tiebreaker, not a primary sort key.
 *
 * Callers must not upper-case the value: mixed case breaks the ordering, since `A-Z` sits
 * between the digits and `a-z` in ASCII.
 */
internal class CountingCorrelationIdGenerator(
    /**
     * Seeded from [SecureRandom] rather than `ThreadLocalRandom`, and the reason is entropy, not
     * security — nothing here is an attack surface.
     *
     * `ThreadLocalRandom` derives its process-wide initial seed from `currentTimeMillis` and
     * `nanoTime` unless `-Djava.util.secureRandomSeed=true` is set. Its output is uniformly
     * distributed, but the set of *reachable* seeds is only as large as the entropy of those two
     * clocks. For pods started seconds apart during a rolling update the wall clock contributes
     * almost nothing, and `nanoTime` shares an origin (host boot time) across containers on the
     * same node, leaving little more than JVM startup jitter. That would invalidate the birthday
     * estimate above by orders of magnitude.
     *
     * The usual objection to [SecureRandom] — blocking, lock contention — applies to the
     * per-call path only. This runs once, during construction.
     */
    prefixSeed: Long = SecureRandom().nextLong(),
    /**
     * Test seam only - production always starts at zero. It exists so the [COUNTER_WIDTH] boundary
     * is testable without 2.8e12 warm-up calls; see the width-boundary test in
     * `CountingCorrelationIdGeneratorTest`.
     */
    counterStart: Long = 0L,
) : CorrelationIdGenerator {
    /**
     * `toUnsignedString` rather than `toString`: half of all long values are negative, and a
     * leading minus sign would both lengthen the id and put a non-alphanumeric character into
     * it. Reinterpreting the same bit pattern as unsigned is a bijection — no entropy is lost.
     */
    private val prefix: String =
        java.lang.Long
            .toUnsignedString(prefixSeed, 36)
            .padStart(PREFIX_WIDTH, '0')

    /**
     * An [AtomicLong], deliberately not a thread-local counter. Under virtual threads a
     * `ThreadLocal` belongs to the virtual thread rather than to its carrier, so every call
     * would start a fresh counter at zero — turning guaranteed uniqueness into guaranteed
     * collisions. The shared atomic is the correct structure here.
     */
    private val counter = AtomicLong(counterStart)

    override fun nextCorrelationId(): String =
        prefix +
            java.lang.Long
                .toUnsignedString(counter.getAndIncrement(), 36)
                .padStart(COUNTER_WIDTH, '0')

    private companion object {
        /**
         * An unsigned 64-bit value renders to at most 13 base-36 digits (36^13 > 2^64 > 36^12),
         * so this width is exactly sufficient and never exceeded.
         */
        private const val PREFIX_WIDTH = 13

        /**
         * 36^8 is about 2.8e12 ids, roughly nine years at a sustained 10,000 ids per second.
         * The relevant horizon is a single instance lifetime, not forever: a restart draws a new
         * prefix and resets the counter, so anything beyond "longer than a pod lives" is wasted
         * width. Eight digits is the conservative rounding of that — seven would already be
         * within reach of a long-lived pod under load.
         *
         * Both widths are load-bearing, and in two independent ways:
         *
         *  1. They make the unseparated concatenation unambiguous. No delimiter is needed
         *     between the two fields because the split point is always at [PREFIX_WIDTH].
         *     Reintroducing a variable-width field would require reintroducing a separator.
         *  2. They keep ids lexicographically ordered, as described in the class documentation.
         *
         * If a value ever exceeds its width, `padStart` simply stops applying: the id grows by
         * a character, and both properties break — with no exception and no log entry, only
         * wrong results from some point in an instance's life onwards. There is deliberately no
         * runtime overflow check, because at this width the case is unreachable and a branch in
         * the hot path would be the wrong trade. The guard is executable instead: the
         * width-boundary test in `CountingCorrelationIdGeneratorTest` drives the counter to the
         * last in-width value through the `counterStart` seam, so anyone narrowing these
         * constants fails the build rather than silently breaking the format.
         */
        private const val COUNTER_WIDTH = 8
    }
}
