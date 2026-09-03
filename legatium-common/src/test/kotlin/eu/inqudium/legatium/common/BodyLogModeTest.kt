package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The truth table of [BodyLogMode]: what is captured, and what reaches the line for which outcome. */
class BodyLogModeTest {
    @Test
    fun `should capture in every mode but never`() {
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
        assertThat(BodyLogMode.ALWAYS.logs(failed = false)).isTrue()
        assertThat(BodyLogMode.ALWAYS.logs(failed = true)).isTrue()
        assertThat(BodyLogMode.NEVER.logs(failed = false)).isFalse()
        assertThat(BodyLogMode.NEVER.logs(failed = true)).isFalse()
    }
}
