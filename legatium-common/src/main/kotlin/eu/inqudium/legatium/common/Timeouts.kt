package eu.inqudium.legatium.common

/**
 * Classifies a failed outbound call as a TIMEOUT: the one client-side disposition worth its own outcome
 * value, because it is the failure an operator reads differently from every other one (the peer is
 * slow or unreachable, not broken). Both twins share the classification (ADR-0003), so the `timeout`
 * outcome means the same thing on the RestClient line and the WebClient line.
 *
 * The decision walks the CAUSE chain (clients wrap: `ResourceAccessException` over
 * `SocketTimeoutException`, `WebClientRequestException` over Netty's `ReadTimeoutException`) and, per
 * link, the class hierarchy BY NAME - the JDK's timeout types are matched as types, Netty's
 * `io.netty.handler.timeout.TimeoutException` family and its CONNECT timeout
 * (`io.netty.channel.ConnectTimeoutException`, a `java.net.ConnectException` that no JDK timeout type
 * covers) by their fully qualified names, so neither twin needs a Netty dependency to recognise a Netty
 * timeout. The other connectors Spring ships need no name of their own: the JDK client's connect timeout
 * is an `HttpTimeoutException`, Jetty raises `SocketTimeoutException` / `TimeoutException`, and Apache
 * HttpComponents 5's `ConnectTimeoutException` IS a `SocketTimeoutException` - pinned per connector by the
 * WebClient twin's connector suites. Anything else is a plain failure.
 */
internal object Timeouts {
    private val timeoutClassNames =
        setOf(
            "java.util.concurrent.TimeoutException",
            "java.net.SocketTimeoutException",
            "java.net.http.HttpTimeoutException",
            "io.netty.handler.timeout.TimeoutException",
            "io.netty.channel.ConnectTimeoutException",
        )

    /** True when [throwable] or any cause is a timeout as defined on this object. Null is never a timeout. */
    fun isTimeout(throwable: Throwable?): Boolean {
        var current = throwable
        // A cause chain is finite in practice, but a cycle is legal; the visited set bounds the walk.
        val visited = HashSet<Throwable>()
        while (current != null && visited.add(current)) {
            if (isTimeoutType(current.javaClass)) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun isTimeoutType(type: Class<*>): Boolean {
        var current: Class<*>? = type
        while (current != null) {
            if (current.name in timeoutClassNames) {
                return true
            }
            current = current.superclass
        }
        return false
    }
}
