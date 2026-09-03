package eu.inqudium.legatium.common

/**
 * One header section (`request-headers` / `response-headers`): which header names are logged, and which
 * of the logged values are masked. Shared by both client-logging twins (ADR-0003): selection semantics
 * and the masking fingerprint are a cross-twin contract.
 *
 * - [includes] names the headers to log; empty means NONE (the safe default). The entry `*` includes
 *   every header the message carries.
 * - [excludes] removes names from the included set - meaningful mainly together with the `*` include;
 *   an exclude always wins over an include. The `*` wildcard is NOT supported here and rejected at
 *   binding time - an empty [includes] already logs nothing, so a wildcard exclude could only be a
 *   misconfiguration that would otherwise fail silently.
 * - [masked] replaces the VALUE of a logged header by what the [HeaderValueMasker] handed to [select]
 *   renders (by default a stable short fingerprint, see [HeaderValueMasker.DEFAULT]); `*` masks every
 *   logged header. Masking only affects headers that are logged at all - listing a name here does not
 *   include it. The masker is a bean, not configuration: the properties say WHICH values are masked,
 *   the host may decide HOW.
 *
 * All matching is case-insensitive, as header names are.
 */
data class HeaderLogProperties(
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val masked: List<String> = emptyList(),
) {
    init {
        require(includes.none { it.isBlank() }) { "includes contains blank entries: $includes" }
        require(excludes.none { it.isBlank() }) { "excludes contains blank entries: $excludes" }
        require(WILDCARD !in excludes) {
            "excludes does not support the '$WILDCARD' wildcard - an empty includes list already logs nothing"
        }
        require(masked.none { it.isBlank() }) { "masked contains blank entries: $masked" }
    }

    // Derived ONCE from the immutable configuration (after the validation above): rebuilding these
    // sets on every call is an allocation on the per-call path.
    private val excludedLower: Set<String> = excludes.map { it.lowercase() }.toSet()
    private val maskedLower: Set<String> = masked.map { it.lowercase() }.toSet()
    private val maskAll: Boolean = WILDCARD in masked
    private val wildcardInclude: Boolean = WILDCARD in includes

    /**
     * The headers this section logs, as `(name, logged value)` pairs: included minus excluded, values
     * masked by [masker] where configured. [availableNames] is consulted only for the `*` include
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
                name to if (maskAll || lower in maskedLower) masker.mask(value) else value
            }
        }
    }

    companion object {
        const val WILDCARD = "*"
    }
}
