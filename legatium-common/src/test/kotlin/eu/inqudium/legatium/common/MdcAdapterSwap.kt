package eu.inqudium.legatium.common

import org.slf4j.MDC
import org.slf4j.spi.MDCAdapter

/**
 * Installs [adapter] as the JVM-global SLF4J MDC adapter for fault injection. SLF4J exposes no public
 * setter, so the package-private `MDC.setMDCAdapter` is invoked reflectively; callers restore the
 * original adapter (`MDC.getMDCAdapter()` taken beforehand) in their teardown. Shared with the twins
 * through this module's test-jar (ADR-0003).
 */
internal fun installMdcAdapter(adapter: MDCAdapter) {
    MDC::class.java
        .getDeclaredMethod("setMDCAdapter", MDCAdapter::class.java)
        .apply { isAccessible = true }
        .invoke(null, adapter)
}
