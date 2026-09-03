package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test

/**
 * Direct contract tests of [HeaderLogProperties] beyond what the filter-level tests exercise: the
 * validation surface, in particular the deliberate rejection of the wildcard in `excludes`.
 */
class HeaderLogPropertiesTest {
    @Test
    fun `should reject the wildcard in excludes at construction time`() {
        // What is tested: the binding-time validation for a plausible misconfiguration - '*' means
        //   something in includes and masked, but was a silent no-op in excludes.
        // Success criteria: construction fails with a message naming the alternative.
        // Why it matters: a wildcard exclude reads like "log nothing", would have logged EVERYTHING the
        //   includes selected, and gave no feedback - the classic silent misconfiguration.
        // Given/When: a section built with a wildcard exclude
        val thrown = catchThrowable { HeaderLogProperties(includes = listOf("*"), excludes = listOf("*")) }

        // Then: rejected, with the empty-includes alternative named
        assertThat(thrown)
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("excludes does not support")
            .hasMessageContaining("includes")
    }

    @Test
    fun `should keep supporting the wildcard in includes and masked`() {
        // Given/When: the two documented wildcard positions
        val section = HeaderLogProperties(includes = listOf("*"), masked = listOf("*"))

        // Then: constructed fine, and selection masks everything it includes
        val selected = section.select(listOf("Accept")) { "text/plain" }
        assertThat(selected).containsExactly("Accept" to HeaderLogProperties.mask("text/plain"))
    }
}
