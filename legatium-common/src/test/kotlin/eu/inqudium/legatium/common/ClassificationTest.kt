package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.event.Level

/**
 * The status half of the classification, shared by both twins (ADR-0012): the outcome by status class,
 * and the four rejections that are WARN. The twins' suites pin that they call it; this one pins the
 * table.
 */
class ClassificationTest {
    @ParameterizedTest
    @ValueSource(ints = [200, 201, 204, 301, 304, 399])
    fun `should classify a non-error status as INFO success`(status: Int) {
        // What is tested: every status below 400 is a success.
        // Success criteria: INFO, success, no cause.
        // Why it matters: a redirect or a 304 completes the call as designed.
        // Given/When
        val classification = Classification.ofStatus(status)

        // Then
        assertThat(classification.level).isEqualTo(Level.INFO)
        assertThat(classification.outcome).isEqualTo(ClientOutcome.SUCCESS)
        assertThat(classification.cause).isNull()
    }

    @Test
    fun `should classify a missing status as INFO success`() {
        // What is tested: the null branch - an exchange the twins hand over without a status line.
        // Success criteria: INFO, success.
        // Why it matters: the twins invent no status; a missing one must not read as a rejection.
        // Given/When/Then
        assertThat(Classification.ofStatus(null).outcome).isEqualTo(ClientOutcome.SUCCESS)
        assertThat(Classification.ofStatus(null).level).isEqualTo(Level.INFO)
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 404, 405, 409, 410, 412, 415, 418, 422, 499])
    fun `should classify a 4xx as INFO rejected`(status: Int) {
        // What is tested: the 4xx class outside the escalation set - the request was wrong, the
        //   application handles it.
        // Success criteria: INFO, rejected, no cause.
        // Why it matters: a 404 on a lookup, a 409 of optimistic locking and a 422 for an end user's
        //   input are regular answer paths; WARN for them would drown the channel (ADR-0012).
        // Given/When
        val classification = Classification.ofStatus(status)

        // Then
        assertThat(classification.level).isEqualTo(Level.INFO)
        assertThat(classification.outcome).isEqualTo(ClientOutcome.REJECTED)
        assertThat(classification.cause).isNull()
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403, 408, 429])
    fun `should escalate the four operator rejections to WARN rejected`(status: Int) {
        // What is tested: the escalation set - identity (401, 403), the peer giving up on us (408),
        //   quota (429).
        // Success criteria: WARN, outcome still rejected, no cause.
        // Why it matters: these are the rejections only an operator can resolve; they must reach the
        //   WARN channel without becoming a failure (ADR-0012).
        // Given/When
        val classification = Classification.ofStatus(status)

        // Then
        assertThat(classification.level).isEqualTo(Level.WARN)
        assertThat(classification.outcome).isEqualTo(ClientOutcome.REJECTED)
        assertThat(classification.cause).isNull()
    }

    @Test
    fun `should pin the escalation set`() {
        // What is tested: the literal set the twins' KDoc and the guides name.
        // Success criteria: exactly 401, 403, 408 and 429.
        // Why it matters: the set is part of the log contract (Common guide "Levels and outcomes"); a
        //   silent addition
        //   would raise the severity of a host's regular answer path.
        // Given/When/Then
        assertThat(Classification.ESCALATED_REJECTIONS).containsExactlyInAnyOrder(401, 403, 408, 429)
    }

    @ParameterizedTest
    @ValueSource(ints = [500, 502, 503, 504, 599])
    fun `should classify a 5xx as WARN failure`(status: Int) {
        // What is tested: the 5xx class - the peer answered that it is broken.
        // Success criteria: WARN, failure, no cause (there was no exception).
        // Why it matters: the level keeps a broken dependency visible; the outcome counts it as failed.
        // Given/When
        val classification = Classification.ofStatus(status)

        // Then
        assertThat(classification.level).isEqualTo(Level.WARN)
        assertThat(classification.outcome).isEqualTo(ClientOutcome.FAILURE)
        assertThat(classification.cause).isNull()
    }
}
