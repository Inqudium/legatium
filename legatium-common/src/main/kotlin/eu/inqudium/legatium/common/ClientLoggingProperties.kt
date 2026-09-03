package eu.inqudium.legatium.common

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration surface of both client-logging twins, bound from the `client-logging.*` namespace. ONE
 * class for the RestClient interceptor and the WebClient filter (ADR-0003): the namespace is a
 * cross-stack contract, key for key and default for default, and the twins' copies were byte-identical.
 * `ClientLoggingReferenceConfigTest` binds the shared reference YAML against this class once.
 *
 * Everything an operator may tune is a Boot property with a safe default, and everything a host
 * application may want to replace wholesale (time source, id generator, the interceptor or filter
 * itself) is an overridable bean instead of a constructor argument.
 * A many-parameter constructor is exactly what this design avoids.
 *
 * Body values are logged verbatim, when their direction's [BodyLogMode] admits the exchange's outcome.
 * Header values are verbatim only when named in the section's [HeaderLogProperties.unmasked]; every
 * other logged value is rendered by the [HeaderValueMasker] bean (by default a stable short fingerprint).
 */
@ConfigurationProperties("client-logging")
data class ClientLoggingProperties(
    /** Master switch; `false` removes the interceptor/filter and its customizers entirely (auto-configuration backs off). */
    val enabled: Boolean = true,
    /**
     * Name of the logger the exchange lines are emitted on. The default is a dedicated, stable name,
     * so log routing and level configuration can target exactly these lines - and can never collide
     * with an inbound exchange logger of the host.
     */
    val loggerName: String = "http-client-exchange",
    /**
     * Header the correlation id is read from on TRACELESS calls (no conformant `traceparent` on the
     * outgoing request - ADR-0002); when absent or blank a new id is generated AND SENT to the peer on
     * this same header name, so the peer can quote it. A traced call takes its request id from the
     * `traceparent` trace id, ignores this header, and adds nothing - the wire stays untouched.
     */
    val correlationIdHeader: String = "X-Correlation-Id",
    /** Whether the request's query string is logged (as its own field, never as part of the path). */
    val includeQueryString: Boolean = true,
    /**
     * Optionally logs a first line the moment the request is SENT, before the wire call - so
     * long-running or never-answered calls are visible while still in flight. The completion event
     * remains the single line carrying `client_outcome`, so outcome-keyed dashboards are unaffected by
     * enabling this.
     */
    val logRequestStart: Boolean = false,
    /**
     * URL patterns (Spring `PathPattern` syntax, e.g. `/api/{*path}`; a trailing double-asterisk wildcard
     * is supported as well) that determine for which request PATHS the module is active AT ALL,
     * whatever the host. Empty (the default) means every call. A call is logged when it matches ANY
     * include pattern and NO exclude prefix and NO excluded host - an exclude always wins, mirroring the
     * header sections' rule. Invalid patterns fail the context start (parsed once at interceptor/filter
     * construction).
     */
    val includePathPatterns: List<String> = emptyList(),
    /**
     * Request-path prefixes that are not logged at all (the module does not even wire an exchange for
     * them). Prefix match against the DECODED path of the request URI (percent-encoding resolved,
     * path parameters dropped), subtracted from the include set.
     */
    val excludePathPrefixes: List<String> = emptyList(),
    /**
     * Peer hosts whose calls are not logged at all - the outbound counterpart of excluding a health
     * probe: a metrics push gateway, a config server, a sidecar. Case-insensitive match against the
     * request URI's host (without port). Subtracted from the include set like a path exclude.
     */
    val excludeHosts: List<String> = emptyList(),
    /**
     * At or above this duration the exchange line escalates from INFO to WARN. Compared at full
     * precision; the logged `client_duration_ms` has millisecond resolution, so the threshold must be
     * at least one millisecond - a sub-millisecond value would flag calls whose logged duration is 0.
     */
    val slowRequestThreshold: Duration = Duration.ofSeconds(5),
    /** Selection and masking of the REQUEST headers on the exchange line; nothing is logged by default. */
    val requestHeaders: HeaderLogProperties = HeaderLogProperties(),
    /** Selection and masking of the RESPONSE headers on the exchange line; nothing is logged by default. */
    val responseHeaders: HeaderLogProperties = HeaderLogProperties(),
    /**
     * When the request body (up to [maxBodyBytes]) is logged: [BodyLogMode.NEVER] (the default),
     * [BodyLogMode.ON_FAILURE] - captured on every call, written only when the outcome is not `success`
     * - or [BodyLogMode.ALWAYS]. The mode, not a switch, is what decides the log volume (ADR-0006).
     */
    val logRequestBody: BodyLogMode = BodyLogMode.NEVER,
    /** As [logRequestBody], for the response body; the outcome is final at emission, so nothing is captured in vain. */
    val logResponseBody: BodyLogMode = BodyLogMode.NEVER,
    /**
     * Whether the request body SIZE is measured (meter `client.request.body.size`, tagged by the URI
     * template and the host). Deliberately independent of [logRequestBody]: a metric must not appear
     * and disappear with a logging flag. Measure-only installs a count-only capture - nothing is
     * buffered. Recorded when the exchange completes (response close resp. the body's terminal
     * signal), and only for bodies that actually flowed (zero bytes record no sample).
     */
    val measureRequestBodySize: Boolean = false,
    /**
     * As [measureRequestBodySize], for the response (`client.response.body.size`), plus the
     * `client.response.body.read` counter that tells an unread response body from an absent one.
     */
    val measureResponseBodySize: Boolean = false,
    /**
     * Capture limit per body. The limit bounds MEMORY, not the exchange: bytes beyond it still flow to the
     * peer respectively the application unchanged, only the log line is truncated (and says so).
     */
    val maxBodyBytes: Int = 16384,
    /**
     * Keys the masking fingerprint: empty (the default) keeps the unkeyed `length:hash` fingerprint,
     * any other value turns it into an HMAC-SHA256 under this key - same shape, same stability under
     * the same key, but guess-proof for a log reader without the key. A SECRET: supply it like one
     * (an environment variable, a vault-backed property), never as a checked-in literal; the
     * properties' `toString` redacts it. Ignored when a host pins its own [HeaderValueMasker] bean.
     */
    val maskingKey: String = "",
) {
    init {
        require(loggerName.isNotBlank()) { "loggerName must not be blank" }
        require(correlationIdHeader.isNotBlank()) { "correlationIdHeader must not be blank" }
        require(HTTP_FIELD_NAME.matches(correlationIdHeader)) {
            "correlationIdHeader must be a valid HTTP field name (RFC 9110 token), got: '$correlationIdHeader'"
        }
        require(maxBodyBytes > 0) { "maxBodyBytes must be positive, got: $maxBodyBytes" }
        require(maskingKey.isEmpty() || maskingKey.isNotBlank()) { "maskingKey must not be blank (leave it empty for the unkeyed fingerprint)" }
        require(slowRequestThreshold.toMillis() >= 1) {
            "slowRequestThreshold must be at least 1 millisecond, got: $slowRequestThreshold"
        }
        require(includePathPatterns.none { it.isBlank() }) {
            "includePathPatterns contains blank entries: $includePathPatterns"
        }
        require(excludePathPrefixes.none { it.isBlank() }) {
            "excludePathPrefixes contains blank entries: $excludePathPrefixes"
        }
        require(excludeHosts.none { it.isBlank() }) {
            "excludeHosts contains blank entries: $excludeHosts"
        }
    }

    /** The data-class rendering minus the secret: a properties dump must never print the masking key. */
    override fun toString(): String = copy(maskingKey = if (maskingKey.isEmpty()) "" else "<redacted>").dataClassToString()

    private fun dataClassToString(): String =
        "ClientLoggingProperties(enabled=$enabled, loggerName=$loggerName, correlationIdHeader=$correlationIdHeader, " +
            "includeQueryString=$includeQueryString, logRequestStart=$logRequestStart, includePathPatterns=$includePathPatterns, " +
            "excludePathPrefixes=$excludePathPrefixes, excludeHosts=$excludeHosts, slowRequestThreshold=$slowRequestThreshold, " +
            "requestHeaders=$requestHeaders, responseHeaders=$responseHeaders, logRequestBody=$logRequestBody, " +
            "logResponseBody=$logResponseBody, measureRequestBodySize=$measureRequestBodySize, " +
            "measureResponseBodySize=$measureResponseBodySize, maxBodyBytes=$maxBodyBytes, maskingKey=$maskingKey)"

    companion object {
        /**
         * RFC 9110 `token` grammar for a field name. The configured name is written onto every traceless
         * outgoing request; an HTTP engine or connector that validates field names (the JDK client does)
         * would reject a non-token at runtime on EVERY call - failing the CALL, not merely the log line
         * - so it is validated at binding time.
         */
        private val HTTP_FIELD_NAME = Regex("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+")
    }
}
