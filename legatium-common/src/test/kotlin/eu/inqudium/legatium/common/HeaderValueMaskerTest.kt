package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test

/** The two built-in maskers: the unkeyed fingerprint and its keyed (HMAC) variant, and the property's factory. */
class HeaderValueMaskerTest {
    @Test
    fun `should key the fingerprint with an HMAC that keeps the shape and changes the digits`() {
        // What is tested: the keyed variant - same `length:hex16` shape, different 64 bits, pinned as
        //   known answers (HMAC-SHA256 over UTF-8, first 8 bytes) so the format cannot drift silently.
        // Success criteria: two keys give two different fingerprints, both differ from the unkeyed one,
        //   and each matches its literal.
        // Why it matters: a keyed fingerprint is what makes `masked` guess-proof; the literals are the
        //   contract a peer sharing the key can rely on.
        // Given/When/Then
        assertThat(HeaderValueMasker.DEFAULT.mask("secret-token")).isEqualTo("12:930bbdc51b6aed5c")
        assertThat(HeaderValueMasker.keyed("k").mask("secret-token")).isEqualTo("12:18da04f7cd594ea3")
        assertThat(HeaderValueMasker.keyed("pepper").mask("secret-token")).isEqualTo("12:3f86c6d54e06207d")
    }

    @Test
    fun `should render identical values identically under the same key`() {
        // What is tested: stability of the keyed fingerprint - within one masker and across two maskers
        //   built from the same key.
        // Success criteria: the same value renders the same string in both cases.
        // Why it matters: a per-instance nonce or salt would keep the shape but break the correlation of a
        //   masked token across events, twins and the inbound sibling.
        // Given/When/Then: stability is what keeps a masked token correlatable
        val masker = HeaderValueMasker.keyed("k")
        assertThat(masker.mask("Bearer x")).isEqualTo(masker.mask("Bearer x"))
        assertThat(masker.mask("Bearer x")).isEqualTo(HeaderValueMasker.keyed("k").mask("Bearer x"))
    }

    @Test
    fun `should select the unkeyed default for an empty key and the keyed variant otherwise`() {
        // What is tested: the property's factory - the empty default keeps the twin-contract fingerprint.
        // Success criteria: empty key -> the DEFAULT instance; a key -> the keyed rendering.
        // Why it matters: the auto-configurations build their bean from this; an empty key must not
        //   silently key the fingerprint with an empty secret.
        // Given/When/Then
        assertThat(HeaderValueMasker.forKey("")).isSameAs(HeaderValueMasker.DEFAULT)
        assertThat(HeaderValueMasker.forKey("k").mask("secret-token")).isEqualTo("12:18da04f7cd594ea3")
    }

    @Test
    fun `should reject a blank key`() {
        // What is tested: the blank-key guard of `keyed`, reached directly and through `forKey`.
        // Success criteria: both throw an IllegalArgumentException.
        // Why it matters: a whitespace key is not empty, so `forKey` would otherwise build an HMAC under a
        //   worthless secret and present the result as guess-proof.
        // Given/When/Then: whitespace is not a secret
        assertThat(catchThrowable { HeaderValueMasker.keyed(" ") }).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(catchThrowable { HeaderValueMasker.forKey("  ") }).isInstanceOf(IllegalArgumentException::class.java)
    }
}
