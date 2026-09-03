package eu.inqudium.legatium.restclient.logging

import org.slf4j.MDC
import org.slf4j.spi.MDCAdapter

// Deliberately DUPLICATED test helper (ADR-0003): test classes are not shared across
// modules (no test-jar dependency), so each module that swaps the MDC adapter carries its own copy.

/**
 * Installs [adapter] as the JVM-global SLF4J MDC adapter for fault injection. SLF4J exposes no public
 * setter, so the package-private `MDC.setMDCAdapter` is invoked reflectively; callers restore the
 * original adapter (`MDC.getMDCAdapter()` taken beforehand) in their teardown.
 */
internal fun installMdcAdapter(adapter: MDCAdapter) {
    MDC::class.java
        .getDeclaredMethod("setMDCAdapter", MDCAdapter::class.java)
        .apply { isAccessible = true }
        .invoke(null, adapter)
}
