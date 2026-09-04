package eu.inqudium.legatium.webclient.logging

import eu.inqudium.legatium.common.ClientLoggingMetrics
import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.CorrelationIdGenerator
import eu.inqudium.legatium.common.HeaderValueMasker
import eu.inqudium.legatium.common.NanoTimeSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.webclient.WebClientCustomizer
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient

/**
 * Contract of [ClientLoggingAutoConfiguration]: present by default in ANY application (no web type
 * required), removable by `adapter-logging.enabled`, every bean overridable, and - the part a unit test
 * cannot show - the customizer actually attaches the filter to the builders Boot hands out.
 */
class ClientLoggingAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ClientLoggingAutoConfiguration::class.java, WebClientAutoConfiguration::class.java))

    @Test
    fun `should register the filter, the defaults and the customizer`() {
        // What is tested: the default bean set of the auto-configuration in a plain context without
        //   a host bean - filter, time source, id generator, masker and the WebClientCustomization
        //   nested config.
        // Success criteria: each type is present exactly once and the customizer bean exists by
        //   name.
        // Why it matters: dropping the module on the classpath is the whole activation story; a
        //   missing default bean would fail the context of every host that does not define its own.
        // Given/When
        contextRunner.run { context ->
            // Then
            assertThat(context).hasSingleBean(ClientRequestLoggingFilter::class.java)
            assertThat(context).hasSingleBean(NanoTimeSource::class.java)
            assertThat(context).hasSingleBean(CorrelationIdGenerator::class.java)
            assertThat(context).hasSingleBean(HeaderValueMasker::class.java)
            assertThat(context).hasBean("clientLoggingWebClientCustomizer")
        }
    }

    @Test
    fun `should attach the filter to every WebClient builder Boot hands out as its last filter`() {
        // What is tested: the customizer path - the filter bean is only useful if Boot's builder carries it.
        // Success criteria: the builder's filter list contains the module's filter as its LAST entry.
        // Why it matters: the shipped activation is this customizer, not the bean.
        // Given/When
        contextRunner.run { context ->
            val filter = context.getBean(ClientRequestLoggingFilter::class.java)
            var filters: List<ExchangeFilterFunction> = emptyList()
            context.getBean(WebClient.Builder::class.java).filters { filters = it.toList() }

            // Then
            assertThat(filters).isNotEmpty()
            assertThat(filters.last()).isSameAs(filter)
        }
    }

    @Test
    fun `should key the default masker from the masking-key property`() {
        // What is tested: the property path to a guess-proof fingerprint - no host bean needed.
        // Success criteria: with masking-key set, the masker bean renders the keyed fingerprint, not the
        //   unkeyed default.
        // Why it matters: keying is the documented answer to "masked is not a security boundary for
        //   guessable values"; it must be reachable from application.yml alone.
        // Given/When
        contextRunner.withPropertyValues("adapter-logging.masking-key=k").run { context ->
            // Then
            assertThat(context.getBean(HeaderValueMasker::class.java).mask("secret-token")).isEqualTo("12:18da04f7cd594ea3")
        }
    }

    @Test
    fun `should back off entirely when disabled by the property`() {
        // What is tested: the class-level @ConditionalOnProperty on `adapter-logging.enabled`.
        // Success criteria: with the property false neither the filter, the defaults, the bound
        //   properties nor the customizer exist.
        // Why it matters: the switch-off must leave no trace - a lingering customizer would still
        //   attach a filter, a lingering default bean could collide with a host's own.
        // Given/When
        contextRunner.withPropertyValues("adapter-logging.enabled=false").run { context ->
            // Then
            assertThat(context).doesNotHaveBean(ClientRequestLoggingFilter::class.java)
            assertThat(context).doesNotHaveBean(NanoTimeSource::class.java)
            assertThat(context).doesNotHaveBean(HeaderValueMasker::class.java)
            assertThat(context).doesNotHaveBean(ClientLoggingProperties::class.java)
            assertThat(context).doesNotHaveBean("clientLoggingWebClientCustomizer")
        }
    }

    @Test
    fun `should bind the identical adapter-logging namespace`() {
        // What is tested: @EnableConfigurationProperties binding of the shared
        //   ClientLoggingProperties under the `adapter-logging` prefix - a scalar, a list, a nested
        //   header section and a boolean.
        // Success criteria: the bound bean carries the four configured values.
        // Why it matters: the RestClient twin binds the same class under the same prefix; a host
        //   with both modules configures them once, so the keys must resolve identically here.
        // Given/When
        contextRunner
            .withPropertyValues(
                "adapter-logging.logger-name=outbound",
                "adapter-logging.exclude-hosts=pushgateway",
                "adapter-logging.request-headers.masked=Authorization",
                "adapter-logging.measure-response-body-size=true",
            ).run { context ->
                // Then
                val properties = context.getBean(ClientLoggingProperties::class.java)
                assertThat(properties.loggerName).isEqualTo("outbound")
                assertThat(properties.excludeHosts).containsExactly("pushgateway")
                assertThat(properties.requestHeaders.masked).containsExactly("Authorization")
                assertThat(properties.measureResponseBodySize).isTrue()
            }
    }

    @Test
    fun `should let a host filter bean win and consume a host registry`() {
        // What is tested: the @ConditionalOnMissingBean back-off for the filter and the masker, and
        //   the ObjectProvider consumption of a host MeterRegistry.
        // Success criteria: the host filter is the single filter bean and the one the builder
        //   carries, the host registry holds the module's three fail-open counters, and the host
        //   masker renders `***`.
        // Why it matters: a host that replaces the filter must not get a second one, and the module
        //   must export into the host's registry rather than define one of its own.
        // Given/When
        contextRunner.withUserConfiguration(HostConfig::class.java).run { context ->
            // Then
            assertThat(context).hasSingleBean(ClientRequestLoggingFilter::class.java)
            assertThat(context.getBean(ClientRequestLoggingFilter::class.java)).isSameAs(context.getBean("hostFilter"))
            var filters: List<ExchangeFilterFunction> = emptyList()
            context.getBean(WebClient.Builder::class.java).filters { filters = it.toList() }
            assertThat(filters.last()).isSameAs(context.getBean("hostFilter"))
            val registry = context.getBean(MeterRegistry::class.java)
            assertThat(registry.find(ClientLoggingMetrics.FAIL_OPEN_METER).counters()).hasSize(3)
            // And: the host's masker backed the default off
            assertThat(context).hasSingleBean(HeaderValueMasker::class.java)
            assertThat(context.getBean(HeaderValueMasker::class.java).mask("x")).isEqualTo("***")
        }
    }

    @Test
    fun `should keep the filter bean without the customizer when Boot's webclient module is absent`() {
        // What is tested: the @ConditionalOnClass(WebClientCustomizer) guard on the nested
        //   customization, with the class hidden by a FilteredClassLoader.
        // Success criteria: the context starts, the filter bean exists, the customizer bean does
        //   not.
        // Why it matters: spring-boot-webclient is an optional dependency; a host building its
        //   clients by hand must still get the filter bean without a ClassNotFoundError at context
        //   start.
        // Given/When: the customizer contract hidden from the classloader
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ClientLoggingAutoConfiguration::class.java))
            .withClassLoader(FilteredClassLoader(WebClientCustomizer::class.java))
            .run { context ->
                // Then
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(ClientRequestLoggingFilter::class.java)
                assertThat(context).doesNotHaveBean("clientLoggingWebClientCustomizer")
            }
    }

    @Test
    fun `should ship the auto-configuration through the imports resource`() {
        // What is tested: the AutoConfiguration.imports resource under META-INF/spring on the test
        //   classpath.
        // Success criteria: one of the imports files names ClientLoggingAutoConfiguration by its
        //   FQCN.
        // Why it matters: Boot discovers auto-configurations only through this file - without the
        //   entry the module is inert on every classpath and no other test would notice.
        // Given/When
        val lines =
            javaClass.classLoader
                .getResources("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                .toList()
                .flatMap { it.readText().lines() }
                .map { it.trim() }

        // Then
        assertThat(lines).contains(ClientLoggingAutoConfiguration::class.java.name)
    }
}

// Host configuration at file level: a @Configuration class local to a test method holds a hidden
// reference to the enclosing test instance, which Spring cannot instantiate as a bean.

@Configuration(proxyBeanMethods = false)
private class HostConfig {
    @Bean
    fun hostMeterRegistry(): MeterRegistry = SimpleMeterRegistry()

    @Bean
    fun hostMasker(): HeaderValueMasker = HeaderValueMasker { "***" }

    @Bean
    fun hostFilter(
        properties: ClientLoggingProperties,
        registry: MeterRegistry,
    ): ClientRequestLoggingFilter = ClientRequestLoggingFilter(properties, NanoTimeSource.SYSTEM, CorrelationIdGenerator.DEFAULT, registry)
}
