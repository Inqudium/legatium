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
    fun `should log on failure only when the exchange did not succeed`() {
        // What is tested: the one decision the emitters delegate - on-failure discards a success.
        assertThat(BodyLogMode.ON_FAILURE.logs(succeeded = true)).isFalse()
        assertThat(BodyLogMode.ON_FAILURE.logs(succeeded = false)).isTrue()
    }

    @Test
    fun `should log always and never regardless of the outcome`() {
        assertThat(BodyLogMode.ALWAYS.logs(succeeded = true)).isTrue()
        assertThat(BodyLogMode.ALWAYS.logs(succeeded = false)).isTrue()
        assertThat(BodyLogMode.NEVER.logs(succeeded = true)).isFalse()
        assertThat(BodyLogMode.NEVER.logs(succeeded = false)).isFalse()
    }
}
