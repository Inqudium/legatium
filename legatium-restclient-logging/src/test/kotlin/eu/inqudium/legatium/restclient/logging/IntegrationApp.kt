package eu.inqudium.legatium.restclient.logging

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean

/**
 * The smallest Boot application that auto-configures the clients and this module - shared by every
 * `@SpringBootTest` of the module - with a host registry, so the meters can be asserted.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
internal class IntegrationApp {
    @Bean
    fun hostMeterRegistry(): MeterRegistry = SimpleMeterRegistry()
}
