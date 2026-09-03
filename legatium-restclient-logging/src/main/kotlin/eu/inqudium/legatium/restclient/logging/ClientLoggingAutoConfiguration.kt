package eu.inqudium.legatium.restclient.logging

import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.CorrelationIdGenerator
import eu.inqudium.legatium.common.NanoTimeSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.restclient.RestClientCustomizer
import org.springframework.boot.restclient.RestTemplateCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

/**
 * Registers the [ClientRequestLoggingInterceptor] in a Spring Boot application and attaches it to every
 * `RestClient` and `RestTemplate` Boot builds - drop the module on the classpath and every outbound call
 * is logged; `client-logging.enabled=false` removes it again. Unlike an inbound filter the interceptor
 * needs no web application: a batch job or a message consumer calling out is a client too.
 *
 * Every bean backs off to a host-provided one: a host may pin [NanoTimeSource] or
 * [CorrelationIdGenerator] (tests do), or define its own [ClientRequestLoggingInterceptor] bean to take
 * over the interceptor while keeping the customizer wiring below.
 *
 * The customizers live in nested configurations conditional on Boot's `spring-boot-restclient` module
 * (an optional dependency of this one): a host that builds its clients by hand keeps the interceptor bean
 * and adds it itself.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "client-logging", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ClientLoggingProperties::class)
class ClientLoggingAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun clientLoggingNanoTimeSource(): NanoTimeSource = NanoTimeSource.SYSTEM

    @Bean
    @ConditionalOnMissingBean
    fun clientLoggingCorrelationIdGenerator(): CorrelationIdGenerator = CorrelationIdGenerator.DEFAULT

    /**
     * The interceptor as its own bean, so a host can replace it while keeping the customizers below.
     *
     * The meter registry arrives as an [ObjectProvider] and is CONSUMED, never exported: a logging
     * library must not define the host's `MeterRegistry`. A host without one - no actuator - gets a
     * private [SimpleMeterRegistry]: the fail-open counters then count unexported, and the module works
     * unchanged.
     */
    @Bean
    @ConditionalOnMissingBean
    fun clientRequestLoggingInterceptor(
        properties: ClientLoggingProperties,
        nanoTime: NanoTimeSource,
        correlationIds: CorrelationIdGenerator,
        meterRegistry: ObjectProvider<MeterRegistry>,
    ): ClientRequestLoggingInterceptor = ClientRequestLoggingInterceptor(properties, nanoTime, correlationIds, meterRegistry.getIfAvailable { SimpleMeterRegistry() })

    /**
     * Attaches the interceptor to every `RestClient.Builder` Boot hands out (and to every HTTP service
     * client group built from one). Ordered LATE among the customizers, so the interceptor is appended
     * behind the interceptors of earlier customizers and runs INSIDE them - closest to the wire, once
     * per attempt of an outer retry (see the interceptor's class documentation).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestClientCustomizer::class)
    class RestClientCustomization {
        @Bean
        @Order(CUSTOMIZER_ORDER)
        fun clientLoggingRestClientCustomizer(interceptor: ClientRequestLoggingInterceptor): RestClientCustomizer = RestClientCustomizer { builder -> builder.requestInterceptor(interceptor) }
    }

    /** As [RestClientCustomization], for every `RestTemplate` built through Boot's `RestTemplateBuilder`. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestTemplateCustomizer::class)
    class RestTemplateCustomization {
        @Bean
        @Order(CUSTOMIZER_ORDER)
        fun clientLoggingRestTemplateCustomizer(interceptor: ClientRequestLoggingInterceptor): RestTemplateCustomizer = RestTemplateCustomizer { restTemplate -> restTemplate.interceptors = restTemplate.interceptors + interceptor }
    }

    companion object {
        /**
         * Late, not last: room is left below for a host customizer that must see the fully configured
         * interceptor list (a metrics or diagnostics wrapper), exactly as the inbound filter's
         * `HIGHEST_PRECEDENCE + 10` leaves room above it.
         */
        const val CUSTOMIZER_ORDER = Ordered.LOWEST_PRECEDENCE - 10
    }
}
