package eu.inqudium.legatium.common

import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser
import java.net.URI

/**
 * Which outbound calls the logging is active for - ONE implementation for both twins (ADR-0003,
 * amendment of 2026-09-04: the two copies were identical), so the activation semantics the guides call
 * "byte-identical by construction" are so by construction.
 *
 * A call is active when its host is not excluded, its path matches ANY include pattern (empty includes
 * = every call) and NO exclude prefix - an exclude always wins. Path matching runs on the raw request
 * path parsed into segments that DECODE for matching; the exclude prefixes compare against the decoded
 * path rebuilt from those segments (path parameters dropped), so a percent-encoded variant cannot slip
 * past an exclude. The include patterns are parsed ONCE at construction: an invalid pattern is a
 * configuration error and fails the context start with the parser's message, instead of failing per
 * call.
 */
internal class ClientActivation(
    private val properties: ClientLoggingProperties,
) {
    private val includePathPatterns: List<PathPattern> =
        properties.includePathPatterns.map { PathPatternParser.defaultInstance.parse(it) }
    private val excludedHosts: Set<String> = properties.excludeHosts.map { it.lowercase() }.toSet()

    /** True when the call to [uri] is NOT logged. */
    fun shouldNotFilter(uri: URI): Boolean {
        if (excludedHosts.isNotEmpty() && uri.host?.lowercase() in excludedHosts) {
            return true
        }
        // Nothing configured to match (the shipped default): active for every call, so the answer
        // needs no PathContainer.
        if (includePathPatterns.isEmpty() && properties.excludePathPrefixes.isEmpty()) {
            return false
        }
        val container = PathContainer.parsePath(uri.rawPath ?: "")
        if (includePathPatterns.isNotEmpty() && includePathPatterns.none { it.matches(container) }) {
            return true
        }
        if (properties.excludePathPrefixes.isEmpty()) {
            return false
        }
        val decodedPath =
            container.elements().joinToString("") { element ->
                if (element is PathContainer.PathSegment) element.valueToMatch() else element.value()
            }
        return properties.excludePathPrefixes.any { decodedPath.startsWith(it) }
    }
}
