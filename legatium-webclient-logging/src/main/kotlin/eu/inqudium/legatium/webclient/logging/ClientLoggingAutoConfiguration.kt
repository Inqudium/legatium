package eu.inqudium.legatium.webclient.logging

import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.ClientLoggingPropertyOrigins
import eu.inqudium.legatium.common.ClientObservationWiring
import eu.inqudium.legatium.common.CorrelationIdGenerator
import eu.inqudium.legatium.common.HeaderValueMasker
import eu.inqudium.legatium.common.NanoTimeSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.BoundConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webclient.WebClientCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.core.io.ResourceLoader

/**
 * Registers the [ClientRequestLoggingFilter] in a Spring Boot application and attaches it to every
 * `WebClient` Boot builds - drop the module on the classpath and every outbound call is logged;
 * `adapter-logging.enabled=false` removes it again. The property namespace matches
 * legatium-restclient-logging's key for key; both modules may even live in one application (a servlet
 * host using `WebClient` for streaming calls), each logging the client it serves.
 *
 * Every bean backs off to a host-provided one - the time source, the id generator, the
 * [HeaderValueMasker] (a keyed fingerprint for a compliance regime), the filter itself. The meter
 * registry arrives as an [ObjectProvider] and is CONSUMED, never exported - a logging library must not
 * define the host's `MeterRegistry`; without one (no actuator) an empty [CompositeMeterRegistry] makes
 * every meter a no-op and the module works unchanged.
 *
 * The customizer lives in a nested configuration conditional on Boot's `spring-boot-webclient` module
 * (an optional dependency of this one): a host that builds its clients by hand keeps the filter bean
 * and adds it itself.
 *
 * ## Observing the wiring
 *
 * At DEBUG on this class's logger the auto-configuration reports what it did, so a host can tell from
 * its own log whether the module is switched on and whether a builder was actually configured: one
 * line when the configuration is active (the switch is on), one when the filter bean is registered
 * (with the bound properties, the masking key redacted), one saying whether the caller's thread-locals
 * are restored around the exchange line (the classpath detection of ADR-0010), one saying whether Boot's
 * client observation and Micrometer Tracing are wired next to the module - the decision behind the
 * identity contract of ADR-0002, which has no property ([ClientObservationWiring]) -, one when the
 * customizer is registered, and one per `WebClient.Builder` the customizer attached the filter to. With
 * `adapter-logging.enabled=false`
 * none of them appears - Boot's condition evaluation report (DEBUG on
 * `org.springframework.boot.autoconfigure`) then names the property as the reason.
 *
 * At TRACE the bean line is followed by the ORIGIN of every `adapter-logging.*` value Boot bound - the
 * file and line, the environment variable, the property source - and by every value of the same name
 * a lower-precedence source also holds, marked as shadowed ([ClientLoggingPropertyOrigins]).
 */
@AutoConfiguration
@ConditionalOnBooleanProperty(prefix = "adapter-logging", name = ["enabled"], matchIfMissing = true)
@EnableConfigurationProperties(ClientLoggingProperties::class)
class ClientLoggingAutoConfiguration {
    init {
        log.debug("Adapter logging is enabled - the auto-configuration is active (adapter-logging.enabled is not false)")
    }

    /** The system's monotonic clock, unless the host pins a time source. */
    @Bean
    @ConditionalOnMissingBean
    fun clientLoggingNanoTimeSource(): NanoTimeSource = NanoTimeSource.SYSTEM

    /** The counting default of ADR-0004, unless the host pins a generator. */
    @Bean
    @ConditionalOnMissingBean
    fun clientLoggingCorrelationIdGenerator(): CorrelationIdGenerator = CorrelationIdGenerator.DEFAULT

    /** The fingerprint the `masking-key` property selects, unless the host pins a masker. */
    @Bean
    @ConditionalOnMissingBean
    fun clientLoggingHeaderValueMasker(properties: ClientLoggingProperties): HeaderValueMasker = HeaderValueMasker.forKey(properties.maskingKey)

    /** The filter as its own bean, so a host can replace it while keeping the customizer below. */
    @Bean
    @ConditionalOnMissingBean
    fun clientRequestLoggingFilter(
        properties: ClientLoggingProperties,
        nanoTime: NanoTimeSource,
        correlationIds: CorrelationIdGenerator,
        masker: HeaderValueMasker,
        meterRegistry: ObjectProvider<MeterRegistry>,
        environment: Environment,
        boundProperties: ObjectProvider<BoundConfigurationProperties>,
        resourceLoader: ResourceLoader,
    ): ClientRequestLoggingFilter {
        log.debug("Adapter logging registered its ClientRequestLoggingFilter bean with {}", properties)
        if (log.isTraceEnabled) {
            val bound = boundProperties.ifAvailable
            if (bound == null) {
                log.trace(
                    "Adapter logging cannot tell where its adapter-logging.* values came from (which file, environment " +
                        "variable or command-line argument set them); the values above are in effect nonetheless. " +
                        "Adapter logging property origins are unavailable - no BoundConfigurationProperties bean in this context",
                )
            } else {
                ClientLoggingPropertyOrigins.describe(bound.all, environment).forEach(log::trace)
            }
        }
        val filter = ClientRequestLoggingFilter(properties, nanoTime, correlationIds, meterRegistry.getIfAvailable { CompositeMeterRegistry() }, masker)
        // The classpath opt-in of ADR-0010 is decided against the CONTEXT's class loader - the one the
        // host's optional dependencies are visible through, and the one a test can narrow - not against
        // the module's own; the filter's constructor default serves manual wiring, where the two coincide.
        // The opt-in has no property; the report is the only place a host can read the outcome.
        filter.emitter.ambientRestorer = AmbientContextRestorer.detect(resourceLoader.classLoader)
        if (filter.emitter.ambientRestorer is ContextPropagationRestorer) {
            log.debug("Adapter logging restores the caller's thread-locals (its MDC) around every exchange line from the Reactor Context - io.micrometer:context-propagation is on the classpath")
        } else {
            log.debug("Adapter logging emits every exchange line with the completing thread's MDC only - io.micrometer:context-propagation is not on the classpath, so the caller's thread-locals are not restored")
        }
        return filter
    }

    /**
     * The observation line of the wiring report ([ClientObservationWiring]) - logged once every singleton
     * exists, because Boot declares its observation customizers under their interface type and only the
     * instance tells them apart from a host's. Independent of the filter bean above: the line is about the
     * context, and appears also when a host replaced the bean.
     */
    @Bean
    fun clientLoggingWebClientObservationReport(beanFactory: ListableBeanFactory): SmartInitializingSingleton =
        SmartInitializingSingleton {
            if (log.isDebugEnabled) {
                log.debug(ClientObservationWiring.describe(beanFactory, OBSERVATION_CUSTOMIZERS))
            }
        }

    /**
     * Attaches the filter to every `WebClient.Builder` Boot hands out (and to every HTTP service client
     * group built from one). Ordered LATE among the customizers, so the filter is appended behind the
     * filters of customizers ordered before [CUSTOMIZER_ORDER] and runs INSIDE them - closest to the
     * connector, once per attempt of an outer retry ([ClientRequestLoggingFilter]). An unordered host
     * customizer runs inside the logging ([CUSTOMIZER_ORDER]).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(WebClientCustomizer::class)
    class WebClientCustomization {
        @Bean
        @Order(CUSTOMIZER_ORDER)
        fun clientLoggingWebClientCustomizer(filter: ClientRequestLoggingFilter): WebClientCustomizer {
            log.debug("Adapter logging registered its WebClientCustomizer - the filter is attached to every WebClient.Builder Boot hands out")
            return WebClientCustomizer { builder ->
                builder.filters { filters ->
                    filters.add(filter)
                    log.debug("Adapter logging attached its filter to a WebClient.Builder behind {} earlier filter(s)", filters.size - 1)
                }
            }
        }
    }

    companion object {
        /**
         * Late, not last: room is left below for a host customizer that must see the fully configured
         * filter list (a metrics or diagnostics wrapper) - the same value as the RestClient twin's.
         *
         * The consequence, decided and pinned by the auto-configuration test: a host customizer without
         * an `@Order` sits at `Ordered.LOWEST_PRECEDENCE`, in that room, and is applied AFTER this one -
         * its filter is appended behind the logging filter and runs inside it, so a header it adds is
         * not on the logged line and a retry it performs is one line for all attempts. A host that wants
         * its filter observed orders its customizer before this value (any explicit `@Order` below
         * `LOWEST_PRECEDENCE - 10`, `@Order(0)` being the usual choice).
         */
        const val CUSTOMIZER_ORDER = Ordered.LOWEST_PRECEDENCE - 10

        /** Boot's observation customizer for the builder this twin attaches to, by class name (an optional class). */
        private val OBSERVATION_CUSTOMIZERS = mapOf("org.springframework.boot.webclient.observation.ObservationWebClientCustomizer" to "WebClient.Builder")

        /** The wiring report of the class KDoc, at DEBUG; the exchange lines have their own logger. */
        private val log = LoggerFactory.getLogger(ClientLoggingAutoConfiguration::class.java)
    }
}
