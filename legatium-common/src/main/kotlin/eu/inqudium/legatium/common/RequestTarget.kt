package eu.inqudium.legatium.common

import java.net.URI

/**
 * The logged coordinates of an outbound request, from its URI: the peer [host] with an explicit port
 * when the URI names one (null for a URI without an authority), the RAW [path] as sent (`/` for an
 * empty one), and the [target] `scheme://host[:port]/path` without the query - the message and MDC
 * coordinate. RAW, still percent-encoded, on purpose: `java.net.URI`'s decoded `getPath()`/`getQuery()`
 * turn `%0A`/`%0D` into real line breaks that would forge lines in every plain-text sink (message, MDC
 * `adapter_route`, fields); activation matching keeps the decoded path. Shared by both twins.
 */
internal class RequestTarget private constructor(
    val host: String?,
    val path: String,
    val target: String,
) {
    companion object {
        fun of(uri: URI): RequestTarget {
            val host = uri.host?.let { if (uri.port != -1) "$it:${uri.port}" else it }
            val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
            val target =
                buildString {
                    uri.scheme?.let { append(it).append("://") }
                    host?.let { append(it) }
                    append(path)
                }
            return RequestTarget(host, path, target)
        }
    }
}
