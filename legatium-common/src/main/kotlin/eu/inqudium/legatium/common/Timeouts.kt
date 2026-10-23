package eu.inqudium.legatium.common

/**
 * Classifies a failed outbound call as a TIMEOUT: the one client-side disposition worth its own outcome
 * value, because it is the failure an operator reads differently from every other one (the peer is
 * slow or unreachable, not broken). Both twins share the classification (ADR-0003), so the `timeout`
 * outcome means the same thing on the RestClient line and the WebClient line.
 *
 * The decision walks the CAUSE graph (clients wrap: `ResourceAccessException` over
 * `SocketTimeoutException`, `WebClientRequestException` over Netty's `ReadTimeoutException`) and, per
 * link, the class hierarchy BY NAME - every listed type, the JDK ones included, is matched by its fully
 * qualified name over the link's superclass chain. That covers Netty's
 * `io.netty.handler.timeout.TimeoutException` family and its CONNECT timeout
 * (`io.netty.channel.ConnectTimeoutException`, a `java.net.ConnectException` that no JDK timeout type
 * covers) without a Netty dependency in either twin. Apache HttpComponents 5 needs one name: its POOL
 * LEASE timeout (`org.apache.hc.core5.http.ConnectionRequestTimeoutException`, raised when no pooled
 * connection became free within the connection request timeout) is an `InterruptedIOException`, a type
 * that also carries a thread interrupt during I/O and so cannot be matched as a whole. Apache
 * HttpClient 4, which Spring no longer ships a request factory for but a host may still bridge with an adapter of
 * its own, needs one more: its `org.apache.http.conn.ConnectTimeoutException` family, an
 * `InterruptedIOException` too - the connect timeout carries the JDK's `SocketTimeoutException` as its
 * cause, but its POOL LEASE timeout (`ConnectionPoolTimeoutException`, a subclass) carries none. The
 * other connectors Spring ships need no name of their own: the JDK client's connect timeout is an
 * `HttpTimeoutException`, Jetty raises `SocketTimeoutException` / `TimeoutException`, Apache's
 * `ConnectTimeoutException` IS a `SocketTimeoutException`, and Reactor Netty's pool acquire timeout is a
 * `java.util.concurrent.TimeoutException` - pinned per engine by the twins' connector suites. Anything
 * else is a plain failure.
 */
internal object Timeouts {
    private val TIMEOUT_CLASS_NAMES =
        setOf(
            "java.util.concurrent.TimeoutException",
            "java.net.SocketTimeoutException",
            "java.net.http.HttpTimeoutException",
            "io.netty.handler.timeout.TimeoutException",
            "io.netty.channel.ConnectTimeoutException",
            "org.apache.hc.core5.http.ConnectionRequestTimeoutException",
            "org.apache.http.conn.ConnectTimeoutException",
        )

    /**
     * True when any throwable of [throwable]'s cause graph (the [CauseGraph] walk: causes, suppressed
     * exceptions, cycles) is a timeout as defined on this object. Null is never a timeout.
     */
    fun isTimeout(throwable: Throwable?): Boolean = CauseGraph.any(throwable) { CauseGraph.hasTypeNamed(it.javaClass, TIMEOUT_CLASS_NAMES) }
}
