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
        val selected = section.select(listOf("Accept"), HeaderValueMasker.DEFAULT) { "text/plain" }
        assertThat(selected).containsExactly("Accept" to HeaderValueMasker.DEFAULT.mask("text/plain"))
    }

    @Test
    fun `should mask through the masker handed to select so a host bean decides the shape`() {
        // What is tested: the masker is an injected collaborator of the selection, not a hard-wired
        //   fingerprint - the shape of a masked value is the host's policy.
        // Success criteria: a section with a masked name renders the masker's output for that header
        //   and the plain value for every other; the plaintext of the masked header never appears.
        // Why it matters: a compliance regime may forbid unkeyed hashes; the bean is the one place to
        //   satisfy it for both twins at once.
        // Given: a keyed stand-in masker
        val section = HeaderLogProperties(includes = listOf("Authorization", "Accept"), masked = listOf("Authorization"))
        val keyed = HeaderValueMasker { "hmac:${it.length}" }
        val values = mapOf("Authorization" to "Bearer secret", "Accept" to "text/plain")

        // When
        val selected = section.select(values.keys, keyed) { values[it] }

        // Then
        assertThat(selected).containsExactly("Authorization" to "hmac:13", "Accept" to "text/plain")
        assertThat(selected.map { it.second }).doesNotContain("Bearer secret")
    }
}
