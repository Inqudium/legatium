package eu.inqudium.legatium.common

import org.slf4j.event.Level

/**
 * What an exchange classifies to at emission: the SLF4J level (severity), the [ClientOutcome] (the
 * semantic - the field a dashboard splits by), and the cause to attach, if any. Severity and semantic
 * are decoupled on purpose: a 5xx answer is WARN with `failure`, a call that threw is ERROR with
 * `failure`, a timeout is WARN with its own outcome, a 4xx is `rejected` at INFO or, for the four
 * statuses of [ESCALATED_REJECTIONS], at WARN. One shape for both twins' emitters; the part that
 * follows from the status alone is [ofStatus], shared so that a 4xx means the same on both lines.
 */
internal class Classification(
    val level: Level,
    val outcome: ClientOutcome,
    val cause: Throwable?,
) {
    companion object {
        /**
         * The 4xx answers whose `rejected` line is WARN instead of INFO (ADR-0012): the peer refused
         * this application for who it is (401, 403), gave up waiting for it (408) or throttles it (429).
         * Those are the rejections only an operator can resolve - credentials, quota, the connection -
         * where every other 4xx says the request was wrong, which the application handles: a 404 on a
         * lookup, a 409 or 412 of optimistic locking, a 400 or 422 the peer returns for an end user's
         * input. The escalation lifts the level only; the outcome stays `rejected`.
         */
        val ESCALATED_REJECTIONS: Set<Int> = setOf(401, 403, 408, 429)

        /**
         * The classification of an exchange that received an answer and neither threw nor was cancelled,
         * by its status alone: a 5xx is WARN `failure` (the peer answered, the application decides), a 4xx
         * is `rejected` - INFO, or WARN for [ESCALATED_REJECTIONS] - and everything else, including a
         * missing status, is INFO `success`.
         */
        fun ofStatus(status: Int?): Classification =
            when {
                status == null -> Classification(Level.INFO, ClientOutcome.SUCCESS, null)
                status >= 500 -> Classification(Level.WARN, ClientOutcome.FAILURE, null)
                status >= 400 -> Classification(if (status in ESCALATED_REJECTIONS) Level.WARN else Level.INFO, ClientOutcome.REJECTED, null)
                else -> Classification(Level.INFO, ClientOutcome.SUCCESS, null)
            }
    }
}
