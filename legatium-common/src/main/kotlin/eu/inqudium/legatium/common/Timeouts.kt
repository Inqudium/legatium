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
 * qualified name over the link's superclass chain, so the engines' own timeout types are recognised
 * without a dependency on any engine in either twin. Each name earns its place by one engine fact, stated
 * at the entry. Rationale: not `java.io.InterruptedIOException` as a whole, although both Apache pool
 * lease timeouts are one - the type also carries a thread interrupt during I/O, which is no timeout; the
 * lease types are therefore listed by their own names. Anything else is a plain failure.
 */
internal object Timeouts {
    private val TIMEOUT_CLASS_NAMES =
        setOf(
            // Assumption: Jetty's idle and total timeouts and Reactor Netty's pool acquire timeout are this
            // type (probed on Jetty 12.1.12 and Reactor Netty 1.3.7; the pool acquire pinned by
            // `ReactorNettyConnectorIntegrationTest`).
            "java.util.concurrent.TimeoutException",
            // Assumption: the JDK `HttpURLConnection` timeouts, Jetty's connect timeout and HttpComponents
            // 5's `org.apache.hc.client5.http.ConnectTimeoutException` are this type (probed on JDK 25 and
            // 26, Jetty 12.1.12, HttpClient 5.6.4).
            "java.net.SocketTimeoutException",
            // Assumption: the JDK `HttpClient`'s request and connect timeouts are this type or its subclass
            // `HttpConnectTimeoutException`, neither a `SocketTimeoutException` (probed on JDK 25 and 26).
            "java.net.http.HttpTimeoutException",
            // Assumption: Netty's read and write timeouts are this family (probed on Netty 4.2.17.Final).
            "io.netty.handler.timeout.TimeoutException",
            // Assumption: Netty's connect timeout is a `java.net.ConnectException` that no JDK timeout type
            // covers (probed on Netty 4.2.17.Final).
            "io.netty.channel.ConnectTimeoutException",
            // Assumption: HttpComponents 5's pool lease timeout - no pooled connection became free within
            // the connection request timeout - is this `InterruptedIOException` (probed on HttpCore 5.4.3).
            "org.apache.hc.core5.http.ConnectionRequestTimeoutException",
            // Assumption: Apache HttpClient 4 - no Spring request factory any more, but a host may bridge
            // it with an adapter of its own - raises this `InterruptedIOException` family: the connect
            // timeout with the JDK's `SocketTimeoutException` as its cause, the pool lease timeout
            // (`ConnectionPoolTimeoutException`, a subclass) with none (probed on HttpClient 4.5.14).
            "org.apache.http.conn.ConnectTimeoutException",
        )

    /**
     * True when any throwable of [throwable]'s cause graph (the [CauseGraph] walk: causes, suppressed
     * exceptions, cycles) is a timeout as defined on this object. Null is never a timeout.
     */
    fun isTimeout(throwable: Throwable?): Boolean = CauseGraph.any(throwable) { CauseGraph.hasTypeNamed(it.javaClass, TIMEOUT_CLASS_NAMES) }
}
