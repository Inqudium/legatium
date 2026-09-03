package eu.inqudium.legatium.restclient.logging

import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.CorrelationIdGenerator
import eu.inqudium.legatium.common.HeaderValueMasker
import eu.inqudium.legatium.common.NanoTimeSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.restclient.RestClientCustomizer
import org.springframework.boot.restclient.RestTemplateBuilder
import org.springframework.boot.restclient.RestTemplateCustomizer
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration
import org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.web.client.RestClient

/**
 * Contract of [ClientLoggingAutoConfiguration]: present by default in ANY application (no web type
 * required), removable by `client-logging.enabled`, every bean overridable, and - the part a unit test
 * cannot show - the customizers actually attach the interceptor to the clients Boot builds.
 */
class ClientLoggingAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    ClientLoggingAutoConfiguration::class.java,
                    RestClientAutoConfiguration::class.java,
                    RestTemplateAutoConfiguration::class.java,
                ),
            )

    @Test
    fun `should register the interceptor, the defaults and both customizers`() {
        // Given/When
        contextRunner.run { context ->
            // Then
            assertThat(context).hasSingleBean(ClientRequestLoggingInterceptor::class.java)
            assertThat(context).hasSingleBean(NanoTimeSource::class.java)
            assertThat(context).hasSingleBean(CorrelationIdGenerator::class.java)
            assertThat(context).hasSingleBean(HeaderValueMasker::class.java)
            assertThat(context).hasBean("clientLoggingRestClientCustomizer")
            assertThat(context).hasBean("clientLoggingRestTemplateCustomizer")
        }
    }

    @Test
    fun `should attach the interceptor to every RestClient builder and RestTemplate Boot hands out`() {
        // What is tested: the customizer path - the interceptor bean is only useful if Boot's builders
        //   carry it.
        // Success criteria: the RestClient.Builder bean's interceptor list and a built RestTemplate's
        //   interceptors both contain the module's interceptor, as their LAST entry.
        // Why it matters: the shipped activation is this customizer, not the bean.
        // Given/When
        contextRunner.run { context ->
            val interceptor = context.getBean(ClientRequestLoggingInterceptor::class.java)
            var interceptors: List<ClientHttpRequestInterceptor> = emptyList()
            context.getBean(RestClient.Builder::class.java).requestInterceptors { interceptors = it.toList() }
            val restTemplate = context.getBean(RestTemplateBuilder::class.java).build()

            // Then
            assertThat(interceptors).isNotEmpty()
            assertThat(interceptors.last()).isSameAs(interceptor)
            assertThat(restTemplate.interceptors.last()).isSameAs(interceptor)
        }
    }

    @Test
    fun `should back off entirely when disabled by the property`() {
        // Given/When
        contextRunner.withPropertyValues("client-logging.enabled=false").run { context ->
            // Then
            assertThat(context).doesNotHaveBean(ClientRequestLoggingInterceptor::class.java)
            assertThat(context).doesNotHaveBean(NanoTimeSource::class.java)
            assertThat(context).doesNotHaveBean(HeaderValueMasker::class.java)
            assertThat(context).doesNotHaveBean(ClientLoggingProperties::class.java)
            assertThat(context).doesNotHaveBean("clientLoggingRestClientCustomizer")
        }
    }

    @Test
    fun `should bind the client-logging namespace`() {
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
    fun `should let a host interceptor bean win and consume a host registry`() {
        // Given/When
        contextRunner.withUserConfiguration(HostConfig::class.java).run { context ->
            // Then: the host's bean alone, wired into Boot's builder; the meters in the host registry
            assertThat(context).hasSingleBean(ClientRequestLoggingInterceptor::class.java)
            assertThat(context.getBean(ClientRequestLoggingInterceptor::class.java)).isSameAs(context.getBean("hostInterceptor"))
            var interceptors: List<ClientHttpRequestInterceptor> = emptyList()
            context.getBean(RestClient.Builder::class.java).requestInterceptors { interceptors = it.toList() }
            assertThat(interceptors.last()).isSameAs(context.getBean("hostInterceptor"))
            val registry = context.getBean(MeterRegistry::class.java)
            assertThat(registry.find(ClientLoggingMetrics.FAIL_OPEN_METER).counters()).hasSize(3)
            // And: the host's masker backed the default off
            assertThat(context).hasSingleBean(HeaderValueMasker::class.java)
            assertThat(context.getBean(HeaderValueMasker::class.java).mask("x")).isEqualTo("***")
        }
    }

    @Test
    fun `should keep the interceptor bean without the customizers when Boot's restclient module is absent`() {
        // What is tested: the optional-dependency boundary - a host wiring clients by hand still gets
        //   the bean to add.
        // Success criteria: with the customizer contracts hidden from the classloader, the context
        //   starts, the interceptor exists, no customizer bean does.
        // Why it matters: an unconditional customizer would fail the context of every such host.
        // Given/When
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ClientLoggingAutoConfiguration::class.java))
            .withClassLoader(FilteredClassLoader(RestClientCustomizer::class.java, RestTemplateCustomizer::class.java))
            .run { context ->
                // Then
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(ClientRequestLoggingInterceptor::class.java)
                assertThat(context).doesNotHaveBean("clientLoggingRestClientCustomizer")
                assertThat(context).doesNotHaveBean("clientLoggingRestTemplateCustomizer")
            }
    }

    @Test
    fun `should ship the auto-configuration through the imports resource`() {
        // Given/When: the merged AutoConfiguration.imports resources on the classpath
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
    fun hostInterceptor(
        properties: ClientLoggingProperties,
        registry: MeterRegistry,
    ): ClientRequestLoggingInterceptor = ClientRequestLoggingInterceptor(properties, NanoTimeSource.SYSTEM, CorrelationIdGenerator.DEFAULT, registry)
}
