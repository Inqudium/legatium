package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.web.util.pattern.PatternParseException
import java.net.URI

/**
 * The activation rule [ClientActivation] driven directly in the module that owns it - the twins' tests
 * show it wired into an entry point, this one pins the rule itself: host exclusion on the peer's NAME
 * (without port, brackets or user info), include patterns and exclude prefixes on the decoded path, and
 * the construction-time failure for an invalid pattern.
 */
class ClientActivationTest {
    private fun activation(
        excludeHosts: List<String> = emptyList(),
        includePathPatterns: List<String> = emptyList(),
        excludePathPrefixes: List<String> = emptyList(),
    ) = ClientActivation(ClientLoggingProperties(excludeHosts = excludeHosts, includePathPatterns = includePathPatterns, excludePathPrefixes = excludePathPrefixes))

    @Test
    fun `should be active for every call with the shipped defaults`() {
        // What is tested: the default configuration - no hosts excluded, no patterns, no prefixes.
        // Success criteria: shouldNotFilter is false for an ordinary and for a relative URI.
        // Why it matters: dropping the module on the classpath must log every call.
        // Given/When/Then
        assertThat(activation().shouldNotFilter(URI.create("https://api.example.com/things"))).isFalse()
        assertThat(activation().shouldNotFilter(URI.create("/relative"))).isFalse()
    }

    @Test
    fun `should exclude a host case-insensitively by its name without the port`() {
        // What is tested: exclude-hosts against the URI's host - the documented exact, case-insensitive
        //   match without port.
        // Success criteria: `Pushgateway` excludes `pushgateway:9091` and `PUSHGATEWAY`; another host
        //   on the same port stays active.
        // Why it matters: the outbound counterpart of excluding a health probe; a port in the
        //   comparison would make the rule depend on how the client was configured.
        // Given
        val activation = activation(excludeHosts = listOf("Pushgateway"))

        // When/Then
        assertThat(activation.shouldNotFilter(URI.create("http://pushgateway:9091/metrics"))).isTrue()
        assertThat(activation.shouldNotFilter(URI.create("http://PUSHGATEWAY/metrics"))).isTrue()
        assertThat(activation.shouldNotFilter(URI.create("http://api.example.com:9091/metrics"))).isFalse()
    }

    @Test
    fun `should exclude a registry-based authority and an IPv6 literal in either bracket form`() {
        // What is tested: the hosts java.net.URI does not parse as a host - a service name with an
        //   underscore - and an IPv6 literal configured with or without brackets.
        // Success criteria: `billing_api` excludes `http://billing_api:8080/x`; both `::1` and
        //   `[::1]` exclude `http://[::1]:8080/`; `[::2]` is not excluded by `::1`.
        // Why it matters: before the fallback such a peer could not be excluded at all (host null),
        //   and an unbracketed IPv6 entry silently matched nothing.
        // Given
        val activation = activation(excludeHosts = listOf("billing_api", "::1", "[fe80::1]"))

        // When/Then
        assertThat(activation.shouldNotFilter(URI.create("http://billing_api:8080/x"))).isTrue()
        assertThat(activation.shouldNotFilter(URI.create("http://[::1]:8080/"))).isTrue()
        assertThat(activation.shouldNotFilter(URI.create("http://[fe80::1]/"))).isTrue()
        assertThat(activation.shouldNotFilter(URI.create("http://[::2]:8080/"))).isFalse()
    }

    @Test
    fun `should include by pattern and exclude by prefix on the decoded path, an exclude winning`() {
        // What is tested: include-path-patterns (PathPattern syntax) and exclude-path-prefixes, with
        //   segments decoding for matching so an encoded variant cannot slip past.
        // Success criteria: `/api/things` and `/%61pi/things` are active under `/api/**`; `/other` is
        //   not; `/api/internal/x` and `/%61pi/internal/x` are excluded by the `/api/internal/`
        //   prefix although the include matches.
        // Why it matters: the exclude must always win, and matching must see the path the way a
        //   server router would.
        // Given
        val activation = activation(includePathPatterns = listOf("/api/**"), excludePathPrefixes = listOf("/api/internal/"))

        // When/Then
        assertThat(activation.shouldNotFilter(URI.create("https://h/api/things"))).isFalse()
        assertThat(activation.shouldNotFilter(URI.create("https://h/%61pi/things"))).isFalse()
        assertThat(activation.shouldNotFilter(URI.create("https://h/other"))).isTrue()
        assertThat(activation.shouldNotFilter(URI.create("https://h/api/internal/x"))).isTrue()
        assertThat(activation.shouldNotFilter(URI.create("https://h/%61pi/internal/x"))).isTrue()
    }

    @Test
    fun `should fail construction for an invalid include pattern`() {
        // What is tested: the include patterns are parsed once at construction.
        // Success criteria: an unbalanced pattern throws Spring's PatternParseException from the
        //   constructor, not from a call.
        // Why it matters: a configuration error must fail the context start with the parser's
        //   message instead of degrading every call to a wiring failure.
        // Given/When
        val thrown = catchThrowable { activation(includePathPatterns = listOf("/api/{unclosed")) }

        // Then
        assertThat(thrown).isInstanceOf(PatternParseException::class.java)
    }
}
