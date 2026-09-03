package eu.inqudium.legatium.common

/**
 * One header section (`request-headers` / `response-headers`): which header names are logged, and which
 * of the logged values appear in plaintext. Shared by both client-logging twins (ADR-0003): selection
 * semantics are a cross-twin contract.
 *
 * MASKED BY DEFAULT (ADR-0005): a logged header's value is replaced by the [HeaderValueMasker]'s
 * rendering unless its name is explicitly allowed in plaintext. The two lists that widen the selection
 * and the one list that removes masking are therefore never coupled the wrong way round: an
 * `includes: ["*"]` typed for a debugging session costs readability, not confidentiality.
 *
 * - [includes] names the headers to log; empty means NONE (the safe default). The entry `*` includes
 *   every header the message carries.
 * - [excludes] removes names from the included set - meaningful mainly together with the `*` include;
 *   an exclude always wins over an include. The `*` wildcard is NOT supported here and rejected at
 *   binding time - an empty [includes] already logs nothing, so a wildcard exclude could only be a
 *   misconfiguration that would otherwise fail silently.
 * - [masked] names the logged headers whose VALUE is replaced by the masker's rendering; the default
 *   `["*"]` masks every logged header. Narrowing it to explicit names is possible but rarely what is
 *   wanted - prefer [unmasked]. An empty list switches masking off wholesale: an explicit, visible
 *   decision, never the accidental result of another list.
 * - [unmasked] names the logged headers that appear in PLAINTEXT although [masked] covers them - the
 *   allowlist of harmless names (`Content-Type`, `Accept`, a correlation id). An unmasked name always
 *   wins over a masked one. The `*` wildcard is rejected here: the plaintext set is an explicit list of
 *   names by design; to log everything in plaintext, empty [masked] instead.
 *
 * Masking (and unmasking) only affects headers that are logged at all - listing a name in [masked] or
 * [unmasked] does not include it. All matching is case-insensitive, as header names are.
 */
data class HeaderLogProperties(
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val masked: List<String> = listOf(WILDCARD),
    val unmasked: List<String> = emptyList(),
) {
    init {
        require(includes.none { it.isBlank() }) { "includes contains blank entries: $includes" }
        require(excludes.none { it.isBlank() }) { "excludes contains blank entries: $excludes" }
        require(WILDCARD !in excludes) {
            "excludes does not support the '$WILDCARD' wildcard - an empty includes list already logs nothing"
        }
        require(masked.none { it.isBlank() }) { "masked contains blank entries: $masked" }
        require(unmasked.none { it.isBlank() }) { "unmasked contains blank entries: $unmasked" }
        require(WILDCARD !in unmasked) {
            "unmasked does not support the '$WILDCARD' wildcard - the plaintext set is an explicit list of names; " +
                "to log every header in plaintext, set masked to an empty list"
        }
    }

    // Derived ONCE from the immutable configuration (after the validation above): rebuilding these
    // sets on every call is an allocation on the per-call path.
    private val excludedLower: Set<String> = excludes.map { it.lowercase() }.toSet()
    private val maskedLower: Set<String> = masked.map { it.lowercase() }.toSet()
    private val unmaskedLower: Set<String> = unmasked.map { it.lowercase() }.toSet()
    private val maskAll: Boolean = WILDCARD in masked
    private val wildcardInclude: Boolean = WILDCARD in includes

    /**
     * The headers this section logs, as `(name, logged value)` pairs: included minus excluded, values
     * masked by [masker] unless the name is unmasked. [availableNames] is consulted only for the `*` include
     * (deduplicated case-insensitively there); explicit includes are looked up directly and keep their
     * configured spelling.
     */
    fun select(
        availableNames: Collection<String>,
        masker: HeaderValueMasker,
        valueOf: (String) -> String?,
    ): List<Pair<String, String>> {
        if (includes.isEmpty()) {
            return emptyList()
        }
        val names = if (wildcardInclude) availableNames.distinctBy { it.lowercase() } else includes
        return names.mapNotNull { name ->
            val lower = name.lowercase()
            if (lower in excludedLower) {
                return@mapNotNull null
            }
            valueOf(name)?.let { value ->
                name to if ((maskAll || lower in maskedLower) && lower !in unmaskedLower) masker.mask(value) else value
            }
        }
    }

    companion object {
        const val WILDCARD = "*"
    }
}
