package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The truth table of [BodyLogMode]: what is captured, and what reaches the line for which outcome. */
class BodyLogModeTest {
    @Test
    fun `should capture in every mode but never`() {
        // What is tested: the `captures` half of the truth table - whether a bounded capture is installed
        //   at all.
        // Success criteria: false for NEVER, true for ON_FAILURE and ALWAYS - on-failure pays the capture
        //   up front.
        // Why it matters: the request body flows before the outcome is known; a mode that logs but does not
        //   capture would have nothing to write when the exchange fails.
        assertThat(BodyLogMode.NEVER.captures).isFalse()
        assertThat(BodyLogMode.ON_FAILURE.captures).isTrue()
        assertThat(BodyLogMode.ALWAYS.captures).isTrue()
    }

    @Test
    fun `should log on failure only when the exchange failed`() {
        // What is tested: the one decision the emitters delegate - on-failure discards a success.
        // Success criteria: true for a failed exchange (outcome not success, or a 4xx), false otherwise.
        // Why it matters: this single predicate is the volume switch of ADR-0006.
        assertThat(BodyLogMode.ON_FAILURE.logs(failed = false)).isFalse()
        assertThat(BodyLogMode.ON_FAILURE.logs(failed = true)).isTrue()
    }

    @Test
    fun `should log always and never regardless of the outcome`() {
        // What is tested: the two unconditional branches of `logs` - the outcome argument is ignored.
        // Success criteria: ALWAYS is true and NEVER is false for both a failed and a successful exchange.
        // Why it matters: NEVER is the default; a body appearing on a failed exchange under it would be a
        //   leak, and ALWAYS dropping a success would silently halve the volume an operator asked for.
        assertThat(BodyLogMode.ALWAYS.logs(failed = false)).isTrue()
        assertThat(BodyLogMode.ALWAYS.logs(failed = true)).isTrue()
        assertThat(BodyLogMode.NEVER.logs(failed = false)).isFalse()
        assertThat(BodyLogMode.NEVER.logs(failed = true)).isFalse()
    }
}
