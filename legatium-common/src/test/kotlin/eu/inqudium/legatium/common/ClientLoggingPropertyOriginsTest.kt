package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.BoundPropertiesTrackingBindHandler
import org.springframework.boot.context.properties.source.ConfigurationProperty
import org.springframework.boot.context.properties.source.ConfigurationPropertyName
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/** The TRACE half of the wiring report: every bound `adapter-logging.*` value with its origin, shadowed values included. */
class ClientLoggingPropertyOriginsTest {
    @Test
    fun `should name the origin of every bound value, list shadowed values and redact the masking key`() {
        // What is tested: describe over an environment with two sources - a higher one setting the
        //   logger name and the masking key, a lower one setting the logger name too and an excluded
        //   host - with the bound map produced by Boot's own binder and tracking handler.
        // Success criteria: one line per effective value, sorted by name, each naming its source; the
        //   lower logger name reported as shadowed by the higher source's origin and indented with "+- "
        //   under the effective value; the masking key rendered redacted; the raw key nowhere in the output.
        // Why it matters: "why is my application.yml value not in effect" is answered by the shadowed
        //   line; a leaked masking key would turn a TRACE report into a secret dump.
        // Given
        val environment =
            StandardEnvironment().apply {
                propertySources.addFirst(MapPropertySource("higher", mapOf("adapter-logging.logger-name" to "outbound", "adapter-logging.masking-key" to "k")))
                propertySources.addLast(MapPropertySource("lower", mapOf("adapter-logging.logger-name" to "base", "adapter-logging.exclude-hosts[0]" to "pushgateway")))
            }

        // When
        val lines = ClientLoggingPropertyOrigins.describe(bind(environment), environment)

        // Then
        assertThat(lines).containsExactly(
            "Adapter logging property adapter-logging.exclude-hosts[0] = pushgateway (origin: \"adapter-logging.exclude-hosts[0]\" from property source \"lower\")",
            "Adapter logging property adapter-logging.logger-name = outbound (origin: \"adapter-logging.logger-name\" from property source \"higher\")",
            "+- Adapter logging property adapter-logging.logger-name = base (origin: \"adapter-logging.logger-name\" from property source \"lower\") " +
                "is shadowed by \"adapter-logging.logger-name\" from property source \"higher\"",
            "Adapter logging property adapter-logging.masking-key = <redacted> (origin: \"adapter-logging.masking-key\" from property source \"higher\")",
        )
        assertThat(lines).noneMatch { it.contains("= k ") }
    }

    @Test
    fun `should map an environment variable to its relaxed property name`() {
        // What is tested: a value set as ADAPTER_LOGGING_MAX_BODY_BYTES in a system-environment source -
        //   the relaxed-binding path an operator uses in a container.
        // Success criteria: the line names the canonical key adapter-logging.max-body-bytes and the value.
        // Why it matters: the report must find the value under the name the operator knows from the
        //   reference configuration, not under the environment variable's spelling.
        // Given
        val environment = StandardEnvironment().apply { propertySources.addFirst(SystemEnvironmentPropertySource("container", mapOf("ADAPTER_LOGGING_MAX_BODY_BYTES" to "1024"))) }

        // When
        val lines = ClientLoggingPropertyOrigins.describe(bind(environment), environment)

        // Then
        assertThat(lines).hasSize(1)
        assertThat(lines.single()).startsWith("Adapter logging property adapter-logging.max-body-bytes = 1024 (origin: ").contains("container")
    }

    @Test
    fun `should say so when no key is set anywhere`() {
        // What is tested: the empty report - an environment without any adapter-logging.* key.
        // Success criteria: exactly one line, stating that every key is at its default.
        // Why it matters: silence would be indistinguishable from TRACE being off.
        // Given/When
        val environment = StandardEnvironment()
        val lines = ClientLoggingPropertyOrigins.describe(bind(environment), environment)

        // Then
        assertThat(lines).containsExactly("Adapter logging properties: no adapter-logging.* key is set in any property source - every key is at its default")
    }

    /** Binds the shared properties the way Boot does for a `@ConfigurationProperties` bean, recording every bound leaf. */
    private fun bind(environment: StandardEnvironment): Map<ConfigurationPropertyName, ConfigurationProperty> {
        val bound = linkedMapOf<ConfigurationPropertyName, ConfigurationProperty>()
        Binder(ConfigurationPropertySources.get(environment)).bind(
            "adapter-logging",
            Bindable.of(ClientLoggingProperties::class.java),
            BoundPropertiesTrackingBindHandler { property -> bound[property.name] = property },
        )
        return bound
    }
}
