package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.PropertySource
import org.springframework.core.io.ClassPathResource

/**
 * Lockstep between the repository-shared reference configuration and the shared
 * [ClientLoggingProperties] both twins inline - the configuration-identity guarantee of the twins, tested
 * once (ADR-0003). The file is loaded exactly as Boot would load it (YamlPropertySourceLoader + Binder),
 * so what the docs show is what an application.yml would do; a property added, renamed or re-defaulted
 * without the reference following - or a documented key that does not exist - fails the build.
 */
class ClientLoggingReferenceConfigTest {
    // The shared reference reaches this module's test classpath through the declared test resource
    // in the POM (the twins declare no docs resources any more).
    private val referenceSources =
        YamlPropertySourceLoader()
            .load("shared-reference", ClassPathResource("client-logging-reference.yml"))

    private fun documentedKeys(sources: List<PropertySource<*>>): Set<String> =
        sources
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { it.propertyNames.asList() }
            .filter { it.startsWith("client-logging.") }
            .map { it.removePrefix("client-logging.").replace(Regex("\\[\\d+]"), "") }
            .toSet()

    @Test
    fun `should bind the reference configuration to exactly the built-in defaults`() {
        // What is tested: that every VALUE in the reference YAML is the built-in default.
        // Success criteria: binding the file yields an object equal to ClientLoggingProperties() - the
        //   data-class equality covers every property at once.
        // Why it matters: the reference promises "copy it, and nothing changes"; a drifted default would
        //   silently break that promise for everyone who copies the block.
        // Given/When: the reference YAML, bound the way Boot binds an application.yml
        val bound =
            Binder(ConfigurationPropertySources.from(referenceSources))
                .bind("client-logging", ClientLoggingProperties::class.java)
                .get()

        // Then: indistinguishable from the untouched defaults
        assertThat(bound).isEqualTo(ClientLoggingProperties())
    }

    @Test
    fun `should document only keys that actually exist and every key that does`() {
        // What is tested: that the reference contains no stale or misspelled keys - the Binder silently
        //   IGNORES unknown keys, so the equality test above cannot catch a typo on its own - and that
        //   no existing key goes undocumented.
        // Success criteria: the client-logging.* key set of the YAML equals the known property names.
        // Why it matters: a documented key that does not bind is worse than an undocumented one - readers
        //   copy it and believe it works.
        // Given: the kebab-case names of all properties
        val knownKeys =
            setOf(
                "enabled",
                "logger-name",
                "correlation-id-header",
                "include-query-string",
                "log-request-start",
                "include-path-patterns",
                "exclude-path-prefixes",
                "exclude-hosts",
                "slow-request-threshold",
                "request-headers.includes",
                "request-headers.excludes",
                "request-headers.masked",
                "response-headers.includes",
                "response-headers.excludes",
                "response-headers.masked",
                "log-request-body",
                "log-response-body",
                "measure-request-body-size",
                "measure-response-body-size",
                "max-body-bytes",
            )

        // When/Then
        assertThat(documentedKeys(referenceSources)).isEqualTo(knownKeys)
    }
}
