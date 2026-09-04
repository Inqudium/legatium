package eu.inqudium.legatium.smoke;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The smallest possible host: Boot's auto-configuration scan, nothing else. Both twins must find their
 * way in through their own {@code META-INF/spring/...AutoConfiguration.imports} inside the shaded jars.
 */
@SpringBootApplication
class SmokeApplication {}
