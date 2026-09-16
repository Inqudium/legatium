package eu.inqudium.legatium.common

import java.net.URI

/**
 * The logged coordinates of an outbound request, from its URI: the peer [host] with an explicit port
 * when the URI names one (null for a URI without an authority), the RAW [path] as sent (`/` for an
 * empty one), and the [target] `scheme://host[:port]/path` without the query - the message and MDC
 * coordinate. RAW, still percent-encoded, on purpose: `java.net.URI`'s decoded `getPath()`/`getQuery()`
 * turn `%0A`/`%0D` into real line breaks that would forge lines in every plain-text sink (message, MDC
 * `adapter_route`, fields); activation matching keeps the decoded path. Shared by both twins.
 *
 * `java.net.URI` refuses to parse an authority that is not a syntactically valid host as one - a service
 * name with an underscore (`billing_api`, common in Compose and Kubernetes) has `getHost()` null and
 * `getPort()` -1 although the request goes to exactly that peer. Such a REGISTRY-BASED authority is
 * taken as the host verbatim (user info stripped), so the coordinates and the exclude-hosts match name
 * the peer instead of rendering `http:///path`. An IPv6 literal keeps its brackets here, as it appears
 * in the URL; [hostName] strips them for the activation match.
 */
internal class RequestTarget private constructor(
    val host: String?,
    val path: String,
    val target: String,
) {
    companion object {
        fun of(uri: URI): RequestTarget {
            val host = uri.host?.let { if (uri.port != -1) "$it:${uri.port}" else it } ?: registryAuthority(uri)
            val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
            val target =
                buildString {
                    uri.scheme?.let { append(it).append("://") }
                    host?.let { append(it) }
                    append(path)
                }
            return RequestTarget(host, path, target)
        }

        /**
         * The peer's host NAME for matching (`exclude-hosts`): without the port, an IPv6 literal without
         * its brackets (`::1`, as an operator writes it), a registry-based authority without user info
         * and port; null for a URI without an authority.
         */
        fun hostName(uri: URI): String? =
            uri.host?.removeSurrounding("[", "]")
                ?: registryAuthority(uri)?.substringBeforeLast(':')

        /**
         * The raw authority without user info when `java.net.URI` could not parse it as a host
         * (`billing_api:8080`); null otherwise.
         */
        private fun registryAuthority(uri: URI): String? = uri.rawAuthority?.substringAfter('@')?.takeIf { it.isNotEmpty() }
    }
}
