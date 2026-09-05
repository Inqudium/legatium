package eu.inqudium.legatium.webclient.logging

import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.CorrelationIdGenerator
import eu.inqudium.legatium.common.HeaderValueMasker
import eu.inqudium.legatium.common.NanoTimeSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webclient.WebClientCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

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
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "adapter-logging", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ClientLoggingProperties::class)
class ClientLoggingAutoConfiguration {
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
    ): ClientRequestLoggingFilter = ClientRequestLoggingFilter(properties, nanoTime, correlationIds, meterRegistry.getIfAvailable { CompositeMeterRegistry() }, masker)

    /**
     * Attaches the filter to every `WebClient.Builder` Boot hands out (and to every HTTP service client
     * group built from one). Ordered LATE among the customizers, so the filter is appended behind the
     * filters of earlier customizers and runs INSIDE them - closest to the connector, once per attempt
     * of an outer retry ([ClientRequestLoggingFilter]).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(WebClientCustomizer::class)
    class WebClientCustomization {
        @Bean
        @Order(CUSTOMIZER_ORDER)
        fun clientLoggingWebClientCustomizer(filter: ClientRequestLoggingFilter): WebClientCustomizer = WebClientCustomizer { builder -> builder.filter(filter) }
    }

    companion object {
        /**
         * Late, not last: room is left below for a host customizer that must see the fully configured
         * filter list (a metrics or diagnostics wrapper) - the same value as the RestClient twin's.
         */
        const val CUSTOMIZER_ORDER = Ordered.LOWEST_PRECEDENCE - 10
    }
}
