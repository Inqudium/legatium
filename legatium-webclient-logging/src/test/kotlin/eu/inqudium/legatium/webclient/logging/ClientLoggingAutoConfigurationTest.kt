package eu.inqudium.legatium.webclient.logging

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
 * required), removable by `client-logging.enabled`, every bean overridable, and - the part a unit test
 * cannot show - the customizer actually attaches the filter to the builders Boot hands out.
 */
class ClientLoggingAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ClientLoggingAutoConfiguration::class.java, WebClientAutoConfiguration::class.java))

    @Test
    fun `should register the filter, the defaults and the customizer`() {
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
    fun `should back off entirely when disabled by the property`() {
        // Given/When
        contextRunner.withPropertyValues("client-logging.enabled=false").run { context ->
            // Then
            assertThat(context).doesNotHaveBean(ClientRequestLoggingFilter::class.java)
            assertThat(context).doesNotHaveBean(NanoTimeSource::class.java)
            assertThat(context).doesNotHaveBean(HeaderValueMasker::class.java)
            assertThat(context).doesNotHaveBean(ClientLoggingProperties::class.java)
            assertThat(context).doesNotHaveBean("clientLoggingWebClientCustomizer")
        }
    }

    @Test
    fun `should bind the identical client-logging namespace`() {
        // Given/When
        contextRunner
            .withPropertyValues(
                "client-logging.logger-name=outbound",
                "client-logging.exclude-hosts=pushgateway",
                "client-logging.request-headers.masked=Authorization",
                "client-logging.measure-response-body-size=true",
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
