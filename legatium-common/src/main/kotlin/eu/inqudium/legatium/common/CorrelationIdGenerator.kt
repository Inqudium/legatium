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
    /** A fresh id for one traceless call - sent on the wire and logged as `adapter_request_id`; it must be usable as an HTTP header value. */
    fun nextCorrelationId(): String

    companion object {
        /**
         * The production default: a [CountingCorrelationIdGenerator] - a random per-JVM base-36
         * prefix plus a monotonically increasing counter, 21 lowercase alphanumeric characters for
         * the first 36^8 ids of an instance lifetime - roughly nine years at a sustained 10,000
         * ids per second, so an operational certainty rather than an unconditional one (NOT a
         * UUID; see that class's documentation for the rationale and the format contract).
         */
        @JvmField
        val DEFAULT: CorrelationIdGenerator = CountingCorrelationIdGenerator()
    }
}

/**
 * Default [CorrelationIdGenerator]: a random per-instance prefix followed by a monotonically increasing
 * counter, both rendered in base 36 and both of fixed width - 21 lowercase alphanumeric characters. The
 * rationale (why not a UUID, the uniqueness model, the entropy source, ordering and widths) is
 * ADR-0004; this class carries the constraints:
 *
 * - The prefix is seeded from [SecureRandom] ONCE, at construction (entropy, not security - see the ADR);
 *   the per-call path is one atomic increment plus a radix conversion, no lock, no I/O.
 * - Uniqueness is guaranteed within an instance and probabilistic across instances (64 bits of prefix).
 * - Ids from one instance sort in allocation order; callers must not upper-case the value.
 * - Both widths are load-bearing (unambiguous concatenation, ordering); exceeding a width breaks both
 *   silently, which the width-boundary test in `CountingCorrelationIdGeneratorTest` guards through the
 *   `counterStart` seam instead of a hot-path check.
 */
internal class CountingCorrelationIdGenerator(
    /** Seeded from [SecureRandom], once, for the entropy of the prefix (ADR-0004). */
    prefixSeed: Long = SecureRandom().nextLong(),
    /** Test seam only - production always starts at zero; lets the [COUNTER_WIDTH] boundary be tested without 2.8e12 warm-up calls. */
    counterStart: Long = 0L,
) : CorrelationIdGenerator {
    /** Rendered UNSIGNED: a leading minus sign would lengthen the id and break the alphabet; the reinterpretation is a bijection. */
    private val prefix: String = prefixSeed.toULong().toString(36).padStart(PREFIX_WIDTH, '0')

    /** Deliberately a shared atomic, not a thread-local counter: under virtual threads a `ThreadLocal` would restart at zero per call. */
    private val counter = AtomicLong(counterStart)

    override fun nextCorrelationId(): String =
        prefix +
            counter
                .getAndIncrement()
                .toULong()
                .toString(36)
                .padStart(COUNTER_WIDTH, '0')

    private companion object {
        /** An unsigned 64-bit value renders to at most 13 base-36 digits (36^13 > 2^64 > 36^12) - exactly sufficient. */
        private const val PREFIX_WIDTH = 13

        /** 36^8 ids - longer than any instance lives; load-bearing for concatenation and ordering, guarded by the width-boundary test (ADR-0004). */
        private const val COUNTER_WIDTH = 8
    }
}
