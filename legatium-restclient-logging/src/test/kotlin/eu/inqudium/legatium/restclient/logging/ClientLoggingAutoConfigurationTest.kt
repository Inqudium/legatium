package eu.inqudium.legatium.restclient.logging

import ch.qos.logback.classic.Level
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
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration
import org.springframework.boot.micrometer.tracing.brave.autoconfigure.BraveAutoConfiguration
import org.springframework.boot.restclient.RestClientCustomizer
import org.springframework.boot.restclient.RestTemplateBuilder
import org.springframework.boot.restclient.RestTemplateCustomizer
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration
import org.springframework.boot.restclient.autoconfigure.RestClientObservationAutoConfiguration
import org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration
import org.springframework.boot.restclient.autoconfigure.RestTemplateObservationAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.core.env.MapPropertySource
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.web.client.RestClient

/**
 * Contract of [ClientLoggingAutoConfiguration]: present by default in ANY application (no web type
 * required), removable by `adapter-logging.enabled`, every bean overridable, and - the part a unit test
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
        // What is tested: the default bean set of the auto-configuration in a plain (non-web)
        //   context with Boot's restclient auto-configurations present - interceptor, the three
        //   @ConditionalOnMissingBean defaults and both nested customizer configurations.
        // Success criteria: exactly one bean each of the interceptor, NanoTimeSource,
        //   CorrelationIdGenerator and HeaderValueMasker, plus the two named customizer beans.
        // Why it matters: a missing default would fail the interceptor's constructor injection, a
        //   missing customizer would leave Boot's clients unlogged; the context runner has no web
        //   type, pinning that no web application is required.
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
            val interceptors = interceptorsOf(context.getBean(RestClient.Builder::class.java))
            val restTemplate = context.getBean(RestTemplateBuilder::class.java).build()

            // Then
            assertThat(interceptors).isNotEmpty()
            assertThat(interceptors.last()).isSameAs(interceptor)
            assertThat(restTemplate.interceptors.last()).isSameAs(interceptor)
        }
    }

    @Test
    fun `should run inside the interceptors of customizers ordered before it and outside those of unordered ones`() {
        // What is tested: the customizer order LOWEST_PRECEDENCE - 10 against competing host
        //   customizers on both builders - one ordered earlier (@Order(0)) and one WITHOUT an order,
        //   which Spring treats as LOWEST_PRECEDENCE and therefore applies AFTER the module's.
        // Success criteria: on the RestClient builder and on a built RestTemplate the interceptor
        //   list reads [earlier host interceptor, module interceptor, unordered host interceptor].
        // Why it matters: "inside the interceptors of earlier customizers" is exactly this - an
        //   unordered host customizer is NOT earlier, its interceptor runs inside the logging and its
        //   header or retry is invisible to the line; the guide documents the rule, this pins it, and a
        //   dropped @Order on the module's customizers would fail here instead of staying green on an
        //   interceptor list of one.
        // Given/When
        contextRunner.withUserConfiguration(CompetingCustomizersConfig::class.java).run { context ->
            val interceptor = context.getBean(ClientRequestLoggingInterceptor::class.java)
            val interceptors = interceptorsOf(context.getBean(RestClient.Builder::class.java))
            val restTemplate = context.getBean(RestTemplateBuilder::class.java).build()

            // Then
            assertThat(interceptors).containsExactly(CompetingCustomizersConfig.EARLIER, interceptor, CompetingCustomizersConfig.UNORDERED)
            assertThat(restTemplate.interceptors).containsExactly(CompetingCustomizersConfig.EARLIER, interceptor, CompetingCustomizersConfig.UNORDERED)
        }
    }

    @Test
    fun `should report at DEBUG that it is enabled and every builder it configured`() {
        // What is tested: the wiring report on the auto-configuration's own logger - the line for the
        //   active switch, the interceptor bean with its properties (masking key redacted), the
        //   observation line for a context without Boot's client observation, the two customizers, and
        //   one line per builder the customizers actually touched.
        // Success criteria: after obtaining Boot's RestClient.Builder and building one RestTemplate,
        //   the DEBUG events contain the enabled line, the bean line naming the bound logger and a
        //   redacted masking key, the no-observation line, both customizer lines, and one attach line
        //   per builder kind, each reporting zero earlier interceptors.
        // Why it matters: an operator asking "is the module on, and did it configure my client?" reads
        //   the answer from the host's log at DEBUG instead of decompiling the customizer order.
        // Given: the auto-configuration's logger captured at DEBUG
        val log = CapturedLogger(ClientLoggingAutoConfiguration::class.java.name, Level.DEBUG)
        try {
            // When
            contextRunner.withPropertyValues("adapter-logging.masking-key=k").run { context ->
                context.getBean(RestClient.Builder::class.java)
                context.getBean(RestTemplateBuilder::class.java).build()

                // Then
                val messages = log.events.filter { it.level == Level.DEBUG }.map { it.formattedMessage }
                assertThat(messages).contains(
                    "Adapter logging is enabled - the auto-configuration is active (adapter-logging.enabled is not false)",
                    "Adapter logging found no client observation - Boot's observation auto-configuration for RestClient.Builder and RestTemplate is not active (no ObservationRegistry bean, or the observation module is absent); the module generates the request id and sends X-Correlation-Id on every call that carries no traceparent",
                    "Adapter logging registered its RestClientCustomizer - the interceptor is attached to every RestClient.Builder Boot hands out",
                    "Adapter logging registered its RestTemplateCustomizer - the interceptor is attached to every RestTemplate built through RestTemplateBuilder",
                    "Adapter logging attached its interceptor to a RestClient.Builder behind 0 earlier interceptor(s)",
                    "Adapter logging attached its interceptor to a RestTemplate behind 0 earlier interceptor(s)",
                )
                assertThat(messages).anySatisfy { message ->
                    assertThat(message)
                        .startsWith("Adapter logging registered its ClientRequestLoggingInterceptor bean with ClientLoggingProperties(")
                        .contains("loggerName=adapter-http-exchange")
                        .contains("maskingKey=<redacted>")
                        .doesNotContain("maskingKey=k")
                }
            }
        } finally {
            log.detach()
        }
    }

    @Test
    fun `should report at DEBUG whether Boot's client observation and tracing are wired`() {
        // What is tested: the observation line of the wiring report against Boot's REAL observation
        //   and tracing auto-configurations - once with a Brave bridge (a Tracer bean), once with the
        //   observation registry alone.
        // Success criteria: with tracing, the line names both builders as observed and traced (trace
        //   id is the request id, no correlation header); without a tracer, the observed-not-traced
        //   line with the generated-id consequence. The no-observation line is pinned by the test above.
        // Why it matters: the decision has no property; this line is where an operator reads why the
        //   peer sees (or does not see) an X-Correlation-Id - and the test breaks when a Boot upgrade
        //   renames the customizer beans the detection looks for.
        // Given: the auto-configuration's logger captured at DEBUG
        val log = CapturedLogger(ClientLoggingAutoConfiguration::class.java.name, Level.DEBUG)
        val observed =
            contextRunner.withConfiguration(
                AutoConfigurations.of(
                    ObservationAutoConfiguration::class.java,
                    RestClientObservationAutoConfiguration::class.java,
                    RestTemplateObservationAutoConfiguration::class.java,
                ),
            )
        try {
            // When/Then: observation with a tracing bridge - the context starts
            observed
                .withConfiguration(AutoConfigurations.of(BraveAutoConfiguration::class.java, MicrometerTracingAutoConfiguration::class.java))
                .run { context ->
                    assertThat(context).hasNotFailed()
                    context.getBean(ClientRequestLoggingInterceptor::class.java)
                }

            // Then
            assertThat(log.events.map { it.formattedMessage }).contains("Adapter logging found Boot's client observation with Micrometer Tracing wired for RestClient.Builder and RestTemplate - every call built there goes out with a traceparent, its trace id is the request id and no X-Correlation-Id is generated")

            // When/Then: observation alone - the context starts
            log.appender.list.clear()
            observed.run { context ->
                assertThat(context).hasNotFailed()
                context.getBean(ClientRequestLoggingInterceptor::class.java)
            }

            // Then
            assertThat(log.events.map { it.formattedMessage }).contains("Adapter logging found Boot's client observation wired for RestClient.Builder and RestTemplate but no Micrometer Tracing - calls are observed, not traced, so the module generates the request id and sends X-Correlation-Id on every call that carries no traceparent")
        } finally {
            log.detach()
        }
    }

    @Test
    fun `should report at TRACE where every adapter-logging value came from`() {
        // What is tested: the TRACE half of the wiring report - the origin of each bound
        //   adapter-logging.* value, a shadowed value from a lower-precedence source, the redacted
        //   masking key, and the empty report when nothing is set.
        // Success criteria: with the logger name and the masking key inlined and a lower source
        //   setting the logger name too, the TRACE events name the runner's inlined source ("test") for the effective
        //   values, mark the lower value as shadowed, render the key redacted and never raw; with no
        //   property set, exactly the one "every key is at its default" line appears.
        // Why it matters: "which file set this, and why is my value not in effect" is answered from
        //   the host's log at TRACE instead of from the actuator's env endpoint in production.
        // Given: the auto-configuration's logger captured at TRACE
        val log = CapturedLogger(ClientLoggingAutoConfiguration::class.java.name, Level.TRACE)
        try {
            // When: two sources, the inlined test properties above a host source
            contextRunner
                .withPropertyValues("adapter-logging.logger-name=outbound", "adapter-logging.masking-key=k")
                .withInitializer { it.environment.propertySources.addLast(MapPropertySource("host-defaults", mapOf("adapter-logging.logger-name" to "base"))) }
                .run { context ->
                    assertThat(context).hasNotFailed()

                    // Then
                    val traces = log.events.filter { it.level == Level.TRACE }.map { it.formattedMessage }
                    assertThat(traces).anySatisfy { line ->
                        assertThat(line).startsWith("Adapter logging property adapter-logging.logger-name = outbound (origin: ").contains("from property source \"test\"")
                    }
                    assertThat(traces).anySatisfy { line ->
                        assertThat(line).startsWith("+- Adapter logging property adapter-logging.logger-name = base (origin: ").contains("host-defaults").contains(") is shadowed by ")
                    }
                    assertThat(traces).anySatisfy { line ->
                        assertThat(line).startsWith("Adapter logging property adapter-logging.masking-key = <redacted> (origin: ")
                    }
                    assertThat(traces).noneMatch { it.contains("masking-key = k") }
                }

            // And: nothing set at all
            val before = log.events.size
            contextRunner.run { context ->
                assertThat(context).hasNotFailed()
                val traces =
                    log.events
                        .drop(before)
                        .filter { it.level == Level.TRACE }
                        .map { it.formattedMessage }
                assertThat(traces).containsExactly("Adapter logging properties: no adapter-logging.* key is set in any property source - every key is at its default")
            }
        } finally {
            log.detach()
        }
    }

    @Test
    fun `should report nothing at DEBUG when disabled by the property`() {
        // What is tested: the wiring report's negative - with the switch off the auto-configuration is
        //   never instantiated, so not even the "enabled" line appears.
        // Success criteria: no event at all on the auto-configuration's logger.
        // Why it matters: the absence of the report is the documented signal for "switched off"; a
        //   line logged from a static initializer or an unconditional bean would make it lie.
        // Given
        val log = CapturedLogger(ClientLoggingAutoConfiguration::class.java.name, Level.DEBUG)
        try {
            // When
            contextRunner.withPropertyValues("adapter-logging.enabled=false").run { context ->
                // Then
                assertThat(context).hasNotFailed()
                assertThat(log.events).isEmpty()
            }
        } finally {
            log.detach()
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
        // What is tested: the class-level @ConditionalOnBooleanProperty on adapter-logging.enabled - with
        //   it false the whole configuration, including @EnableConfigurationProperties and the nested
        //   customizer classes, is skipped.
        // Success criteria: neither the interceptor, the defaults, the bound properties nor the
        //   customizer bean exists.
        // Why it matters: the kill switch must remove every trace of the module, not only the log
        //   line - a leftover customizer or default bean would still shadow a host's own beans.
        // Given/When
        contextRunner.withPropertyValues("adapter-logging.enabled=false").run { context ->
            // Then
            assertThat(context).doesNotHaveBean(ClientRequestLoggingInterceptor::class.java)
            assertThat(context).doesNotHaveBean(NanoTimeSource::class.java)
            assertThat(context).doesNotHaveBean(HeaderValueMasker::class.java)
            assertThat(context).doesNotHaveBean(ClientLoggingProperties::class.java)
            assertThat(context).doesNotHaveBean("clientLoggingRestClientCustomizer")
        }
    }

    @Test
    fun `should bind the adapter-logging namespace`() {
        // What is tested: property binding of ClientLoggingProperties under the adapter-logging
        //   prefix - a scalar, a list, a nested header section and a boolean, through Boot's relaxed
        //   binding.
        // Success criteria: the bound bean reports logger-name, exclude-hosts,
        //   request-headers.masked and measure-response-body-size exactly as configured.
        // Why it matters: the prefix and the nested section names are the documented configuration
        //   contract; a rename in the properties class would silently ignore an operator's YAML.
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
    fun `should let a host interceptor bean win and consume a host registry`() {
        // What is tested: the @ConditionalOnMissingBean back-off for the interceptor and the
        //   masker, with the host-defined interceptor still handed to the customizer and the host's
        //   MeterRegistry receiving the module's meters.
        // Success criteria: the single interceptor bean is the host's, Boot's builder carries it as
        //   the last interceptor, the host registry holds the three fail-open counters, and the
        //   single masker renders the host's "***".
        // Why it matters: a host must be able to replace the interceptor or the masking policy
        //   without losing the customizer wiring, and the meters must land in the exported registry
        //   rather than a private one.
        // Given/When
        contextRunner.withUserConfiguration(HostConfig::class.java).run { context ->
            // Then: the host's bean alone, wired into Boot's builder; the meters in the host registry
            assertThat(context).hasSingleBean(ClientRequestLoggingInterceptor::class.java)
            assertThat(context.getBean(ClientRequestLoggingInterceptor::class.java)).isSameAs(context.getBean("hostInterceptor"))
            assertThat(interceptorsOf(context.getBean(RestClient.Builder::class.java)).last()).isSameAs(context.getBean("hostInterceptor"))
            val registry = context.getBean(MeterRegistry::class.java)
            assertThat(registry.find(ClientLoggingMetrics.FAIL_OPEN_METER).counters()).hasSize(3)
            // And: the host's masker backed the default off
            assertThat(context).hasSingleBean(HeaderValueMasker::class.java)
            assertThat(context.getBean(HeaderValueMasker::class.java).mask("x")).isEqualTo("***")
        }
    }

    @Test
    fun `should let host time source and id generator beans back the defaults off`() {
        // What is tested: the @ConditionalOnMissingBean back-off for the two remaining
        //   collaborators, NanoTimeSource and CorrelationIdGenerator, with the host pinning both and
        //   nothing else - the interceptor and the masker stay the auto-configured defaults.
        // Success criteria: exactly one bean of each collaborator type, each the host's instance
        //   (same reference, host behaviour on a call), while the interceptor and the masker still
        //   exist exactly once.
        // Why it matters: a deterministic clock and id generator are the documented override for a
        //   test profile and for a peer that insists on an id format; the back-off is what makes the
        //   host bean reach the interceptor's constructor injection instead of colliding with a
        //   second bean of the same type.
        // Given/When
        contextRunner.withUserConfiguration(HostCollaboratorsConfig::class.java).run { context ->
            // Then: the host's collaborators alone
            assertThat(context).hasSingleBean(NanoTimeSource::class.java)
            assertThat(context.getBean(NanoTimeSource::class.java)).isSameAs(context.getBean("hostNanoTime"))
            assertThat(context.getBean(NanoTimeSource::class.java).nanoTime()).isEqualTo(42L)
            assertThat(context).hasSingleBean(CorrelationIdGenerator::class.java)
            assertThat(context.getBean(CorrelationIdGenerator::class.java)).isSameAs(context.getBean("hostCorrelationIds"))
            assertThat(context.getBean(CorrelationIdGenerator::class.java).nextCorrelationId()).isEqualTo("host-id")
            // And: the defaults the host did not touch are still there, once each
            assertThat(context).hasSingleBean(ClientRequestLoggingInterceptor::class.java)
            assertThat(context).hasSingleBean(HeaderValueMasker::class.java)
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
        // What is tested: the META-INF/spring/...AutoConfiguration.imports resource of the module -
        //   the registration mechanism Boot 3+ uses instead of spring.factories.
        // Success criteria: the merged import lines on the classpath contain the fully qualified
        //   class name of ClientLoggingAutoConfiguration.
        // Why it matters: the context-runner tests register the class explicitly; only this
        //   resource makes the module active by merely being on a host's classpath.
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

    /** The interceptors a builder carries, read through the builder's inspection callback. */
    private fun interceptorsOf(builder: RestClient.Builder): List<ClientHttpRequestInterceptor> = buildList { builder.requestInterceptors { addAll(it) } }
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

/** Two host customizers per builder kind: one ordered before the module's, one without an order (= LOWEST_PRECEDENCE). */
@Configuration(proxyBeanMethods = false)
private class CompetingCustomizersConfig {
    @Bean
    @Order(0)
    fun earlierRestClientCustomizer(): RestClientCustomizer = RestClientCustomizer { it.requestInterceptor(EARLIER) }

    @Bean
    fun unorderedRestClientCustomizer(): RestClientCustomizer = RestClientCustomizer { it.requestInterceptor(UNORDERED) }

    @Bean
    @Order(0)
    fun earlierRestTemplateCustomizer(): RestTemplateCustomizer = RestTemplateCustomizer { it.interceptors = it.interceptors + EARLIER }

    @Bean
    fun unorderedRestTemplateCustomizer(): RestTemplateCustomizer = RestTemplateCustomizer { it.interceptors = it.interceptors + UNORDERED }

    companion object {
        val EARLIER = ClientHttpRequestInterceptor { request, body, execution -> execution.execute(request, body) }
        val UNORDERED = ClientHttpRequestInterceptor { request, body, execution -> execution.execute(request, body) }
    }
}

@Configuration(proxyBeanMethods = false)
private class HostCollaboratorsConfig {
    @Bean
    fun hostNanoTime(): NanoTimeSource = NanoTimeSource { 42L }

    @Bean
    fun hostCorrelationIds(): CorrelationIdGenerator = CorrelationIdGenerator { "host-id" }
}
