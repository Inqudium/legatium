package eu.inqudium.legatium.common

import org.slf4j.MDC
import org.slf4j.spi.MDCAdapter
import java.util.Deque

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

/**
 * The failing MDC adapter the fault-injection tests install through [installMdcAdapter]. It delegates
 * everything to [delegate], so MDC state stays real, and while [armed] it refuses `put` of a key in
 * [failPut] with what [putFailure] builds (an `IllegalStateException` unless a test wants an `Error`),
 * `remove` of a key in [failRemove], and `getCopyOfContextMap` when [failCopy] is set. Armed from the
 * start, unless a test arms it around exactly the call it targets. Shared with the twins through this
 * module's test-jar (ADR-0003).
 */
internal class FailingMdcAdapter(
    private val delegate: MDCAdapter,
    var failPut: Set<String> = emptySet(),
    var failRemove: Set<String> = emptySet(),
    @Volatile var armed: Boolean = true,
) : MDCAdapter by delegate {
    var putFailure: (String) -> Throwable = { key -> IllegalStateException("adapter put failed for $key") }
    var failCopy = false

    override fun put(
        key: String,
        value: String?,
    ) {
        if (armed && key in failPut) throw putFailure(key)
        delegate.put(key, value)
    }

    override fun remove(key: String) {
        if (armed && key in failRemove) error("adapter remove failed for $key")
        delegate.remove(key)
    }

    override fun getCopyOfContextMap(): MutableMap<String, String>? {
        if (armed && failCopy) error("adapter copy failed")
        return delegate.copyOfContextMap
    }

    override fun pushByKey(
        key: String,
        value: String,
    ) = delegate.pushByKey(key, value)

    override fun popByKey(key: String): String? = delegate.popByKey(key)

    override fun getCopyOfDequeByKey(key: String): Deque<String>? = delegate.getCopyOfDequeByKey(key)

    override fun clearDequeByKey(key: String) = delegate.clearDequeByKey(key)
}
