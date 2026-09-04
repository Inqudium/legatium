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

    /**
     * True when [throwable], any cause, or any SUPPRESSED exception (recursively) is a timeout as defined
     * on this object - Reactor's composite errors (`Exceptions.multiple` from `zip`/`merge`/`when`) carry
     * their components as suppressed, not as the cause. Null is never a timeout.
     */
    fun isTimeout(throwable: Throwable?): Boolean {
        // A cause graph is finite in practice, but a cycle is legal; the visited set bounds the walk.
        val visited = HashSet<Throwable>()
        val pending = ArrayDeque<Throwable>()
        throwable?.let(pending::add)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) {
                continue
            }
            if (isTimeoutType(current.javaClass)) {
                return true
            }
            current.cause?.let(pending::add)
            current.suppressed.forEach(pending::add)
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
