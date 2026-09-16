package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * The logged coordinates [RequestTarget] derives from a request URI, driven directly in the module that
 * owns them: the ordinary host/port/path shape, the raw (still percent-encoded) path, and the
 * authorities `java.net.URI` will not parse as a host - a service name with an underscore, an IPv6
 * literal - which must still name the peer.
 */
class RequestTargetTest {
    @Test
    fun `should derive host with port, raw path and target from an ordinary URI`() {
        // What is tested: RequestTarget.of on the common shape - scheme, host, explicit port, an
        //   encoded path and a query.
        // Success criteria: host carries the port, the path stays percent-encoded, the target has no
        //   query, and hostName drops the port.
        // Why it matters: these three strings are the message, the MDC route and the host field of
        //   every line; a decoded path would let %0A forge line breaks in plain-text sinks.
        // Given/When
        val target = RequestTarget.of(URI.create("https://api.example.com:8443/things/a%20b?x=1"))

        // Then
        assertThat(target.host).isEqualTo("api.example.com:8443")
        assertThat(target.path).isEqualTo("/things/a%20b")
        assertThat(target.target).isEqualTo("https://api.example.com:8443/things/a%20b")
        assertThat(RequestTarget.hostName(URI.create("https://api.example.com:8443/things"))).isEqualTo("api.example.com")
    }

    @Test
    fun `should default an empty path to a slash and leave the host out for a URI without an authority`() {
        // What is tested: the two edges - a URI without a path and a relative URI without an
        //   authority.
        // Success criteria: `/` as the path and in the target; null host and hostName for the
        //   relative URI, whose target is then only the path.
        // Why it matters: the route must never render empty, and a host must not be invented.
        // Given/When/Then
        val bare = RequestTarget.of(URI.create("https://api.example.com"))
        assertThat(bare.path).isEqualTo("/")
        assertThat(bare.target).isEqualTo("https://api.example.com/")
        val relative = RequestTarget.of(URI.create("/relative/path"))
        assertThat(relative.host).isNull()
        assertThat(relative.target).isEqualTo("/relative/path")
        assertThat(RequestTarget.hostName(URI.create("/relative/path"))).isNull()
    }

    @Test
    fun `should take a registry-based authority as the host when URI refuses to parse it`() {
        // What is tested: a service name with an underscore (`billing_api`, common in Compose and
        //   Kubernetes), for which java.net.URI reports host null and port -1 although the authority
        //   is there - with and without a port, and with user info to strip.
        // Success criteria: host and target name the peer as written (port included), hostName gives
        //   the bare name, user info never appears.
        // Why it matters: without the fallback the line rendered `http:///x`, the host field was
        //   missing and exclude-hosts could not match the peer at all.
        // Given/When/Then
        val withPort = RequestTarget.of(URI.create("http://billing_api:8080/x"))
        assertThat(withPort.host).isEqualTo("billing_api:8080")
        assertThat(withPort.target).isEqualTo("http://billing_api:8080/x")
        assertThat(RequestTarget.hostName(URI.create("http://billing_api:8080/x"))).isEqualTo("billing_api")

        val bare = RequestTarget.of(URI.create("http://billing_api/x"))
        assertThat(bare.host).isEqualTo("billing_api")
        assertThat(RequestTarget.hostName(URI.create("http://billing_api/x"))).isEqualTo("billing_api")

        val withUserInfo = RequestTarget.of(URI.create("http://user:secret@billing_api:8080/x"))
        assertThat(withUserInfo.host).isEqualTo("billing_api:8080")
        assertThat(withUserInfo.target).doesNotContain("secret")
        assertThat(RequestTarget.hostName(URI.create("http://user:secret@billing_api:8080/x"))).isEqualTo("billing_api")
    }

    @Test
    fun `should keep the brackets of an IPv6 literal in the coordinates and strip them for the host name`() {
        // What is tested: an IPv6 literal, which java.net.URI parses as the bracketed host.
        // Success criteria: host and target show `[::1]:8080` as the URL does; hostName gives `::1`,
        //   the form an operator writes into exclude-hosts.
        // Why it matters: `exclude-hosts: ["::1"]` silently matched nothing before, because the
        //   comparison saw the brackets.
        // Given/When
        val uri = URI.create("http://[::1]:8080/health")

        // Then
        assertThat(RequestTarget.of(uri).host).isEqualTo("[::1]:8080")
        assertThat(RequestTarget.of(uri).target).isEqualTo("http://[::1]:8080/health")
        assertThat(RequestTarget.hostName(uri)).isEqualTo("::1")
    }
}
