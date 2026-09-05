package eu.inqudium.legatium.common

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Redacts the VALUE of a logged header - every one, by default (ADR-0005), unless a section's
 * [HeaderLogProperties.unmasked] allows the name in plaintext - before it reaches the log line. The
 * rendering is a stable PSEUDONYM, not anonymisation: equal values stay recognisable as equal, which is
 * the point (correlation) and the limit (a keyed masker is what stops a reader from confirming a guess).
 *
 * Injectable for the same reason as [NanoTimeSource] and [CorrelationIdGenerator]: the
 * fingerprint's shape is a policy the host may own - a keyed HMAC for a compliance regime that forbids
 * unkeyed hashes, a fixed `***` for a host that wants no correlation at all - and both twins take the
 * bean through their auto-configuration (`@ConditionalOnMissingBean`), so one host bean masks the
 * RestClient line and the WebClient line alike.
 *
 * Two built-ins cover the common cases without a bean of the host's own: [DEFAULT], the unkeyed
 * fingerprint, and [keyed], the same fingerprint over an HMAC - which is what the `masking-key`
 * property selects ([forKey]). A host with a stricter policy still pins its own bean.
 *
 * The contract is the same as the built-in default's: the returned string replaces the value on the
 * line and MUST NOT contain the plaintext. Whether it is stable (equal values, equal output) is the
 * implementation's choice - stability is what makes a masked token correlatable across events; a host
 * that trades it away does so knowingly.
 */
fun interface HeaderValueMasker {
    /** The rendering that replaces [value] on the log line; MUST NOT contain the plaintext. */
    fun mask(value: String): String

    companion object {
        /**
         * The production default: the value's character length followed by the first 64 bits of its
         * SHA-256 digest (UTF-8) in lowercase hex: `secret-token` renders as `12:930bbdc51b6aed5c`. The
         * same fingerprint in both twin modules and in the sibling project limesium's inbound line - both
         * repositories pin that very literal in their `HeaderValueMasker` tests - so a masked token
         * correlates across the server line and the client line. STABLE: identical values render
         * identically; a 64-bit cryptographic prefix makes accidental collisions negligible.
         *
         * Privacy model: the fingerprint is unsalted and unkeyed - it prevents PLAINTEXT exposure, not
         * offline guessing. A log reader with a candidate list (low-entropy values: usernames, tenant
         * names, short API keys) can confirm a candidate by hashing it. Do not treat `masked` as a
         * security boundary for guessable values; omit such headers from the selection instead - or
         * key the fingerprint ([keyed], the `masking-key` property).
         */
        @JvmField
        val DEFAULT: HeaderValueMasker = FingerprintHeaderValueMasker(null)

        /**
         * The keyed variant of [DEFAULT]: the same `length:hex` shape, but the 64 bits come from an
         * HMAC-SHA256 over the value under [key] (UTF-8) instead of a bare digest. Same stability -
         * identical values under the same key render identically, so correlation across events, twins
         * and the inbound sibling holds as long as they share the key - but a log reader without the
         * key can no longer confirm a guessed value by hashing it: the fingerprint is guess-proof to
         * the strength of the key. An HMAC rather than a concatenated salt, because that is the
         * construction with the proof; the key is a secret and must be treated as one (a Boot secret,
         * never a checked-in literal). [key] must not be blank; an empty key means unkeyed - see
         * [forKey].
         */
        @JvmStatic
        fun keyed(key: String): HeaderValueMasker {
            require(key.isNotBlank()) { "masking key must not be blank" }
            return FingerprintHeaderValueMasker(key)
        }

        /**
         * The masker the `masking-key` property selects: [DEFAULT] for the empty string (the property's
         * default - unkeyed), [keyed] otherwise. The auto-configurations build their default bean from
         * this, and the entry points' constructors default their masker parameter to it - so keying the
         * fingerprint needs no bean of the host's own, and a manually constructed interceptor or filter
         * honours the key exactly like an auto-configured one. The property is the one source of truth;
         * only an explicitly passed masker (the host's bean) overrides it.
         */
        @JvmStatic
        fun forKey(key: String): HeaderValueMasker = if (key.isEmpty()) DEFAULT else keyed(key)
    }
}

/** The built-in fingerprint: unkeyed SHA-256 for a null [key], HMAC-SHA256 under the key otherwise. */
private class FingerprintHeaderValueMasker(
    key: String?,
) : HeaderValueMasker {
    private val secret: SecretKeySpec? = key?.let { SecretKeySpec(it.toByteArray(StandardCharsets.UTF_8), HMAC) }

    override fun mask(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        // A Mac instance is not thread-safe; one per call is the correct trade for a path that
        // runs once per masked header. HexFormat instead of a per-byte "%02x".format:
        // byte-identical output at a fraction of the allocation.
        val digest =
            if (secret == null) {
                MessageDigest.getInstance("SHA-256").digest(bytes)
            } else {
                Mac.getInstance(HMAC).apply { init(secret) }.doFinal(bytes)
            }
        return "${value.length}:${HEX.formatHex(digest, 0, FINGERPRINT_BYTES)}"
    }

    private companion object {
        private const val HMAC = "HmacSHA256"
        private const val FINGERPRINT_BYTES = 8
        private val HEX = HexFormat.of()
    }
}
