package eu.inqudium.legatium.restclient.logging

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
import org.springframework.boot.restclient.RestClientCustomizer
import org.springframework.boot.restclient.RestTemplateCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment

/**
 * Registers the [ClientRequestLoggingInterceptor] in a Spring Boot application and attaches it to every
 * `RestClient` and `RestTemplate` Boot builds - drop the module on the classpath and every outbound call
 * is logged; `adapter-logging.enabled=false` removes it again. Unlike an inbound filter the interceptor
 * needs no web application: a batch job or a message consumer calling out is a client too.
 *
 * Every bean backs off to a host-provided one: a host may pin [NanoTimeSource] or
 * [CorrelationIdGenerator] (tests do), replace the [HeaderValueMasker] (a keyed fingerprint for a
 * compliance regime), or define its own [ClientRequestLoggingInterceptor] bean to take over the
 * interceptor while keeping the customizer wiring below.
 *
 * The customizers live in nested configurations conditional on Boot's `spring-boot-restclient` module
 * (an optional dependency of this one): a host that builds its clients by hand keeps the interceptor bean
 * and adds it itself.
 *
 * ## Observing the wiring
 *
 * At DEBUG on this class's logger the auto-configuration reports what it did, so a host can tell from
 * its own log whether the module is switched on and whether a builder was actually configured: one
 * line when the configuration is active (the switch is on), one when the interceptor bean is registered
 * (with the bound properties, the masking key redacted), one saying whether Boot's client observation
 * and Micrometer Tracing are wired next to the module - the decision behind the identity contract of
 * ADR-0002, which has no property ([ClientObservationWiring]) -, one per customizer registered, and one
 * per `RestClient.Builder` or `RestTemplate` the customizers attached the interceptor to. With
 * `adapter-logging.enabled=false` none of them appears - Boot's condition evaluation report (DEBUG on
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

    /**
     * The interceptor as its own bean, so a host can replace it while keeping the customizers below.
     *
     * The meter registry arrives as an [ObjectProvider] and is CONSUMED, never exported: a logging
     * library must not define the host's `MeterRegistry`. A host without one - no actuator - gets an
     * empty [CompositeMeterRegistry]: Micrometer's meters against it are no-ops, so nothing is
     * accumulated in a registry nobody reads, and the module works unchanged.
     */
    @Bean
    @ConditionalOnMissingBean
    fun clientRequestLoggingInterceptor(
        properties: ClientLoggingProperties,
        nanoTime: NanoTimeSource,
        correlationIds: CorrelationIdGenerator,
        masker: HeaderValueMasker,
        meterRegistry: ObjectProvider<MeterRegistry>,
        environment: Environment,
        boundProperties: ObjectProvider<BoundConfigurationProperties>,
    ): ClientRequestLoggingInterceptor {
        log.debug("Adapter logging registered its ClientRequestLoggingInterceptor bean with {}", properties)
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
        return ClientRequestLoggingInterceptor(properties, nanoTime, correlationIds, meterRegistry.getIfAvailable { CompositeMeterRegistry() }, masker)
    }

    /**
     * The observation line of the wiring report ([ClientObservationWiring]) - logged once every singleton
     * exists, because Boot declares its observation customizers under their interface type and only the
     * instance tells them apart from a host's. Independent of the interceptor bean above: the line is about the
     * context, and appears also when a host replaced the bean.
     */
    @Bean
    fun clientLoggingRestClientObservationReport(beanFactory: ListableBeanFactory): SmartInitializingSingleton =
        SmartInitializingSingleton {
            if (log.isDebugEnabled) {
                log.debug(ClientObservationWiring.describe(beanFactory, OBSERVATION_CUSTOMIZERS))
            }
        }

    /**
     * Attaches the interceptor to every `RestClient.Builder` Boot hands out (and to every HTTP service
     * client group built from one). Ordered LATE among the customizers, so the interceptor is appended
     * behind the interceptors of customizers ordered before [CUSTOMIZER_ORDER] and runs INSIDE them -
     * closest to the wire, once per attempt of an outer retry ([ClientRequestLoggingInterceptor]). An
     * unordered host customizer runs inside the logging ([CUSTOMIZER_ORDER]).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestClientCustomizer::class)
    class RestClientCustomization {
        @Bean
        @Order(CUSTOMIZER_ORDER)
        fun clientLoggingRestClientCustomizer(interceptor: ClientRequestLoggingInterceptor): RestClientCustomizer {
            log.debug("Adapter logging registered its RestClientCustomizer - the interceptor is attached to every RestClient.Builder Boot hands out")
            return RestClientCustomizer { builder ->
                builder.requestInterceptors { interceptors ->
                    interceptors.add(interceptor)
                    log.debug("Adapter logging attached its interceptor to a RestClient.Builder behind {} earlier interceptor(s)", interceptors.size - 1)
                }
            }
        }
    }

    /** As [RestClientCustomization], for every `RestTemplate` built through Boot's `RestTemplateBuilder`. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestTemplateCustomizer::class)
    class RestTemplateCustomization {
        @Bean
        @Order(CUSTOMIZER_ORDER)
        fun clientLoggingRestTemplateCustomizer(interceptor: ClientRequestLoggingInterceptor): RestTemplateCustomizer {
            log.debug("Adapter logging registered its RestTemplateCustomizer - the interceptor is attached to every RestTemplate built through RestTemplateBuilder")
            return RestTemplateCustomizer { restTemplate ->
                val earlier = restTemplate.interceptors.size
                restTemplate.interceptors = restTemplate.interceptors + interceptor
                log.debug("Adapter logging attached its interceptor to a RestTemplate behind {} earlier interceptor(s)", earlier)
            }
        }
    }

    companion object {
        /**
         * Late, not last: room is left below for a host customizer that must see the fully configured
         * interceptor list (a metrics or diagnostics wrapper), exactly as the inbound filter's
         * `HIGHEST_PRECEDENCE + 10` leaves room above it.
         *
         * The consequence, decided and pinned by the auto-configuration test: a host customizer without
         * an `@Order` sits at `Ordered.LOWEST_PRECEDENCE`, in that room, and is applied AFTER this one -
         * its interceptor is appended behind the logging interceptor and runs inside it, so a header it
         * adds is not on the logged line and a retry it performs is one line for all attempts. A host
         * that wants its interceptor observed orders its customizer before this value (any explicit
         * `@Order` below `LOWEST_PRECEDENCE - 10`, `@Order(0)` being the usual choice).
         */
        const val CUSTOMIZER_ORDER = Ordered.LOWEST_PRECEDENCE - 10

        /** Boot's observation customizers for the two builders this twin attaches to, by class name (optional classes). */
        private val OBSERVATION_CUSTOMIZERS =
            mapOf(
                "org.springframework.boot.restclient.observation.ObservationRestClientCustomizer" to "RestClient.Builder",
                "org.springframework.boot.restclient.observation.ObservationRestTemplateCustomizer" to "RestTemplate",
            )

        /** The wiring report of the class KDoc, at DEBUG; the exchange lines have their own logger. */
        private val log = LoggerFactory.getLogger(ClientLoggingAutoConfiguration::class.java)
    }
}
