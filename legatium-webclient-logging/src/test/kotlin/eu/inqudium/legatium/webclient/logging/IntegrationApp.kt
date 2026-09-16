package eu.inqudium.legatium.webclient.logging

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

/** The smallest Boot application that auto-configures the client and this module - shared by every `@SpringBootTest` of the module. */
@SpringBootConfiguration
@EnableAutoConfiguration
internal class IntegrationApp
