package eu.inqudium.legatium.webclient.logging

import ch.qos.logback.classic.Level
import eu.inqudium.legatium.common.ClientLoggingMetrics
import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.CorrelationIdGenerator
import eu.inqudium.legatium.common.HeaderValueMasker
import eu.inqudium.legatium.common.NanoTimeSource
import io.micrometer.context.ContextSnapshotFactory
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration
import org.springframework.boot.micrometer.tracing.brave.autoconfigure.BraveAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.webclient.WebClientCustomizer
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration
import org.springframework.boot.webclient.autoconfigure.WebClientObservationAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.core.env.MapPropertySource
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

    /** The filters [builder] holds, in order - `filters` hands its list to the callback synchronously. */
    private fun filtersOf(builder: WebClient.Builder): List<ExchangeFilterFunction> = buildList { builder.filters { addAll(it) } }

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
            val filters = filtersOf(context.getBean(WebClient.Builder::class.java))

            // Then
            assertThat(filters).isNotEmpty()
            assertThat(filters.last()).isSameAs(filter)
        }
    }

    @Test
    fun `should run inside the filters of customizers ordered before it and outside those of unordered ones`() {
        // What is tested: the customizer order LOWEST_PRECEDENCE - 10 against competing host
        //   customizers - one ordered earlier (@Order(0)) and one WITHOUT an order, which Spring
        //   treats as LOWEST_PRECEDENCE and therefore applies AFTER the module's.
        // Success criteria: the builder's filter list reads [earlier host filter, module filter,
        //   unordered host filter].
        // Why it matters: "inside the filters of earlier customizers" is exactly this - an unordered
        //   host customizer is NOT earlier, its filter runs inside the logging and its header or retry
        //   is invisible to the line; the guide documents the rule, this pins it, and a dropped @Order
        //   on the module's customizer would fail here instead of staying green on a filter list of one.
        // Given/When
        contextRunner.withUserConfiguration(CompetingCustomizersConfig::class.java).run { context ->
            val filter = context.getBean(ClientRequestLoggingFilter::class.java)
            val filters = filtersOf(context.getBean(WebClient.Builder::class.java))

            // Then
            assertThat(filters).containsExactly(CompetingCustomizersConfig.EARLIER, filter, CompetingCustomizersConfig.UNORDERED)
        }
    }

    @Test
    fun `should report at DEBUG that it is enabled and every builder it configured`() {
        // What is tested: the wiring report on the auto-configuration's own logger - the line for the
        //   active switch, the filter bean with its properties (masking key redacted), the outcome of the
        //   context-propagation detection (ADR-0010), the observation line for a context without Boot's
        //   client observation, the customizer, and one line per WebClient.Builder the customizer
        //   actually touched.
        // Success criteria: after obtaining Boot's WebClient.Builder twice, the DEBUG events contain
        //   the enabled line, the bean line naming the bound logger and a redacted masking key, the
        //   restore line for a classpath that has context-propagation (this test's), the
        //   no-observation line, the customizer line, and two attach lines each reporting zero earlier
        //   filters.
        // Why it matters: an operator asking "is the module on, and did it configure my client?" reads
        //   the answer from the host's log at DEBUG instead of decompiling the customizer order.
        // Given: the auto-configuration's logger captured at DEBUG
        val log = CapturedLogger(ClientLoggingAutoConfiguration::class.java.name, Level.DEBUG)
        try {
            // When
            contextRunner.withPropertyValues("adapter-logging.masking-key=k").run { context ->
                context.getBean(WebClient.Builder::class.java)
                context.getBean(WebClient.Builder::class.java)

                // Then
                val messages = log.events.filter { it.level == Level.DEBUG }.map { it.formattedMessage }
                assertThat(messages).contains(
                    "Adapter logging is enabled - the auto-configuration is active (adapter-logging.enabled is not false)",
                    "Adapter logging restores the caller's thread-locals (its MDC) around every exchange line from the Reactor Context - io.micrometer:context-propagation is on the classpath",
                    "Adapter logging found no client observation - Boot's observation auto-configuration for WebClient.Builder is not active (no ObservationRegistry bean, or the observation module is absent); the module generates the request id and sends X-Correlation-Id on every call that carries no traceparent",
                    "Adapter logging registered its WebClientCustomizer - the filter is attached to every WebClient.Builder Boot hands out",
                )
                assertThat(messages.filter { it == "Adapter logging attached its filter to a WebClient.Builder behind 0 earlier filter(s)" }).hasSize(2)
                assertThat(messages).anySatisfy { message ->
                    assertThat(message)
                        .startsWith("Adapter logging registered its ClientRequestLoggingFilter bean with ClientLoggingProperties(")
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
    fun `should report at DEBUG that the caller's MDC is not restored when context-propagation is absent`() {
        // What is tested: the restore line of the wiring report against a context whose class loader
        //   cannot see io.micrometer:context-propagation - the detection runs against the context's
        //   loader, not the module's.
        // Success criteria: the absent variant of the line, and the filter's emitter holds the no-op
        //   restorer; the present variant nowhere.
        // Why it matters: a host without the optional library reads from its log why client lines lack
        //   the inbound identity - and the test proves the line follows the classpath the host has, not
        //   the one this module was built with.
        // Given: the auto-configuration's logger captured at DEBUG
        val log = CapturedLogger(ClientLoggingAutoConfiguration::class.java.name, Level.DEBUG)
        try {
            // When
            contextRunner.withClassLoader(FilteredClassLoader(ContextSnapshotFactory::class.java)).run { context ->
                assertThat(context).hasNotFailed()

                // Then
                assertThat(context.getBean(ClientRequestLoggingFilter::class.java).emitter.ambientRestorer).isSameAs(AmbientContextRestorer.NONE)
                val messages = log.events.map { it.formattedMessage }
                assertThat(messages).contains(
                    "Adapter logging emits every exchange line with the completing thread's MDC only - io.micrometer:context-propagation is not on the classpath, so the caller's thread-locals are not restored",
                )
                assertThat(messages).noneMatch { it.contains("context-propagation is on the classpath") }
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
        // Success criteria: with tracing, the line names the builder as observed and traced (trace id
        //   is the request id, no correlation header); without a tracer, the observed-not-traced line
        //   with the generated-id consequence. The no-observation line is pinned by the test above.
        // Why it matters: the decision has no property; this line is where an operator reads why the
        //   peer sees (or does not see) an X-Correlation-Id - and the test breaks when a Boot upgrade
        //   renames the customizer bean the detection looks for.
        // Given: the auto-configuration's logger captured at DEBUG
        val log = CapturedLogger(ClientLoggingAutoConfiguration::class.java.name, Level.DEBUG)
        val observed = contextRunner.withConfiguration(AutoConfigurations.of(ObservationAutoConfiguration::class.java, WebClientObservationAutoConfiguration::class.java))
        try {
            // When: observation with a tracing bridge
            observed
                .withConfiguration(AutoConfigurations.of(BraveAutoConfiguration::class.java, MicrometerTracingAutoConfiguration::class.java))
                .run { context ->
                    assertThat(context).hasNotFailed()
                    context.getBean(ClientRequestLoggingFilter::class.java)
                }

            // Then
            assertThat(log.events.map { it.formattedMessage }).contains("Adapter logging found Boot's client observation with Micrometer Tracing wired for WebClient.Builder - every call built there goes out with a traceparent, its trace id is the request id and no X-Correlation-Id is generated")

            // When: observation alone
            log.appender.list.clear()
            observed.run { context ->
                assertThat(context).hasNotFailed()
                context.getBean(ClientRequestLoggingFilter::class.java)
            }

            // Then
            assertThat(log.events.map { it.formattedMessage }).contains("Adapter logging found Boot's client observation wired for WebClient.Builder but no Micrometer Tracing - calls are observed, not traced, so the module generates the request id and sends X-Correlation-Id on every call that carries no traceparent")
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

            // And when: nothing set at all
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
        // What is tested: the class-level @ConditionalOnBooleanProperty on `adapter-logging.enabled`.
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
            assertThat(filtersOf(context.getBean(WebClient.Builder::class.java)).last()).isSameAs(context.getBean("hostFilter"))
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
        //   nothing else - the filter and the masker stay the auto-configured defaults.
        // Success criteria: exactly one bean of each collaborator type, each the host's instance
        //   (same reference, host behaviour on a call), while the filter and the masker still
        //   exist exactly once.
        // Why it matters: a deterministic clock and id generator are the documented override for a
        //   test profile and for a peer that insists on an id format; the back-off is what makes the
        //   host bean reach the filter's constructor injection instead of colliding with a
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
            assertThat(context).hasSingleBean(ClientRequestLoggingFilter::class.java)
            assertThat(context).hasSingleBean(HeaderValueMasker::class.java)
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

/** Two host customizers: one ordered before the module's, one without an order (= LOWEST_PRECEDENCE). */
@Configuration(proxyBeanMethods = false)
private class CompetingCustomizersConfig {
    @Bean
    @Order(0)
    fun earlierWebClientCustomizer(): WebClientCustomizer = WebClientCustomizer { it.filter(EARLIER) }

    @Bean
    fun unorderedWebClientCustomizer(): WebClientCustomizer = WebClientCustomizer { it.filter(UNORDERED) }

    companion object {
        val EARLIER = ExchangeFilterFunction { request, next -> next.exchange(request) }
        val UNORDERED = ExchangeFilterFunction { request, next -> next.exchange(request) }
    }
}

@Configuration(proxyBeanMethods = false)
private class HostCollaboratorsConfig {
    @Bean
    fun hostNanoTime(): NanoTimeSource = NanoTimeSource { 42L }

    @Bean
    fun hostCorrelationIds(): CorrelationIdGenerator = CorrelationIdGenerator { "host-id" }
}
