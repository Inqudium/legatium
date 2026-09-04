package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test

/**
 * Direct contract tests of [HeaderLogProperties] beyond what the filter-level tests exercise: the
 * mask-by-default rule (ADR-0005), the `unmasked` allowlist, and the validation surface - in particular
 * the deliberate rejection of the wildcard in `excludes` and in `unmasked`.
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
    fun `should mask every logged header by default and let unmasked names through in plaintext`() {
        // What is tested: ADR-0005 - the default section masks everything it logs; the plaintext set is
        //   an explicit allowlist that wins over the mask.
        // Success criteria: with a wildcard include and NOTHING said about masking, both headers are
        //   fingerprinted; naming one in unmasked renders exactly that one in plaintext.
        // Why it matters: `includes: ["*"]` is the documented debugging move; with the old defaults it
        //   put every header in the log in plaintext because masking was a second, empty list.
        // Given: a wildcard include with the default masking
        val everything = HeaderLogProperties(includes = listOf("*"))
        val values = mapOf("Authorization" to "Bearer secret", "Accept" to "text/plain")

        // When/Then: both masked
        assertThat(everything.select(values.keys, HeaderValueMasker.DEFAULT) { values[it] })
            .containsExactly(
                "Authorization" to HeaderValueMasker.DEFAULT.mask("Bearer secret"),
                "Accept" to HeaderValueMasker.DEFAULT.mask("text/plain"),
            )

        // When/Then: the allowlisted name is plain, the other stays masked
        val allowing = HeaderLogProperties(includes = listOf("*"), unmasked = listOf("accept"))
        assertThat(allowing.select(values.keys, HeaderValueMasker.DEFAULT) { values[it] })
            .containsExactly(
                "Authorization" to HeaderValueMasker.DEFAULT.mask("Bearer secret"),
                "Accept" to "text/plain",
            )
    }

    @Test
    fun `should switch masking off only through an explicitly emptied masked list`() {
        // What is tested: the documented way back to plaintext - `masked = []` clears the `maskAll` flag
        //   and the masked set at once.
        // Success criteria: an explicitly included Authorization value renders in plaintext.
        // Why it matters: the mask-by-default rule needs exactly one visible off switch; if emptying the
        //   list did not work an operator would reach for a less visible route.
        // Given: masking emptied on purpose
        val plain = HeaderLogProperties(includes = listOf("Authorization"), masked = emptyList())

        // When/Then: plaintext - the visible decision, never the accidental one
        assertThat(plain.select(listOf("Authorization"), HeaderValueMasker.DEFAULT) { "Bearer secret" })
            .containsExactly("Authorization" to "Bearer secret")
    }

    @Test
    fun `should reject the wildcard in unmasked at construction time`() {
        // What is tested: the plaintext set is an explicit list of names by design.
        // Success criteria: construction fails with a message naming the alternative (empty masked).
        // Why it matters: `unmasked: ["*"]` would be the one-token way back to plaintext-everything; the
        //   way back must be the visible removal of the mask, not an addition that reads harmless.
        // Given/When
        val thrown = catchThrowable { HeaderLogProperties(includes = listOf("*"), unmasked = listOf("*")) }

        // Then
        assertThat(thrown)
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unmasked does not support")
            .hasMessageContaining("masked to an empty list")
    }

    @Test
    fun `should keep supporting the wildcard in includes and masked`() {
        // What is tested: the two positions where `*` is legal - the validation must reject it only in
        //   excludes and unmasked.
        // Success criteria: construction succeeds and a header the wildcard includes is masked.
        // Why it matters: `masked: ["*"]` is the default value; a validation tightened by mistake would
        //   fail every context that spells the default out.
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

    @Test
    fun `should log an explicitly included header once although it is listed twice in differing case`() {
        // What is tested: deduplication of the explicit include path, which the wildcard path already had.
        // Success criteria: `["Accept", "accept"]` selects the header once, under the first spelling.
        // Why it matters: a duplicated name=value pair on the line is the one input the validation does
        //   not normalise.
        // Given
        val section = HeaderLogProperties(includes = listOf("Accept", "accept"), masked = emptyList())

        // When
        val selected = section.select(listOf("accept"), HeaderValueMasker.DEFAULT) { "text/plain" }

        // Then
        assertThat(selected).containsExactly("Accept" to "text/plain")
    }

    @Test
    fun `should reject blank entries in every list at construction time`() {
        // What is tested: the four blank-entry checks - the validation surface the masking fuzz target
        //   relies on (it catches the IllegalArgumentException) but never proves.
        // Success criteria: each list rejects a blank entry with a message naming the list.
        // Why it matters: a blank name binds silently otherwise and matches nothing, an operator's typo
        //   turning into a missing header without feedback.
        // Given/When/Then
        val cases =
            listOf(
                "includes" to { HeaderLogProperties(includes = listOf(" ")) },
                "excludes" to { HeaderLogProperties(excludes = listOf("")) },
                "masked" to { HeaderLogProperties(masked = listOf("Authorization", " ")) },
                "unmasked" to { HeaderLogProperties(unmasked = listOf("\t")) },
            )
        cases.forEach { (list, construct) ->
            assertThat(catchThrowable { construct() })
                .describedAs(list)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("$list contains blank entries")
        }
    }
}
