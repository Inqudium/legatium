package eu.inqudium.legatium.common

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Redacts the VALUE of a header listed in a section's [HeaderLogProperties.masked] before it reaches the
 * log line. Injectable for the same reason as [NanoTimeSource] and [CorrelationIdGenerator]: the
 * fingerprint's shape is a policy the host may own - a keyed HMAC for a compliance regime that forbids
 * unkeyed hashes, a fixed `***` for a host that wants no correlation at all - and both twins take the
 * bean through their auto-configuration (`@ConditionalOnMissingBean`), so one host bean masks the
 * RestClient line and the WebClient line alike.
 *
 * The contract is the same as the built-in default's: the returned string replaces the value on the
 * line and MUST NOT contain the plaintext. Whether it is stable (equal values, equal output) is the
 * implementation's choice - stability is what makes a masked token correlatable across events; a host
 * that trades it away does so knowingly.
 */
fun interface HeaderValueMasker {
    fun mask(value: String): String

    companion object {
        /**
         * The production default: the value's character length followed by the first 64 bits of its
         * SHA-256 digest (UTF-8) in lowercase hex, e.g. `18:930bbdc51b6aed5c` - the same fingerprint in
         * both twin modules, and the same scheme the sibling project limesium uses on the inbound side,
         * so a masked token correlates across the server line and the client line. STABLE: identical
         * values render identically; a 64-bit cryptographic prefix makes accidental collisions negligible.
         *
         * Privacy model: the fingerprint is unsalted and unkeyed - it prevents PLAINTEXT exposure, not
         * offline guessing. A log reader with a candidate list (low-entropy values: usernames, tenant
         * names, short API keys) can confirm a candidate by hashing it. Do not treat `masked` as a
         * security boundary for guessable values; omit such headers from the selection instead - or
         * pin a keyed masker bean.
         */
        val DEFAULT: HeaderValueMasker = FingerprintHeaderValueMasker

        private object FingerprintHeaderValueMasker : HeaderValueMasker {
            private val hex = HexFormat.of()
            private const val FINGERPRINT_BYTES = 8

            override fun mask(value: String): String {
                val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
                // HexFormat instead of a per-byte "%02x".format: byte-identical output at a fraction
                // of the allocation.
                return "${value.length}:${hex.formatHex(digest, 0, FINGERPRINT_BYTES)}"
            }
        }
    }
}
