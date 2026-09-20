package eu.inqudium.legatium.webclient.logging

import eu.inqudium.legatium.common.NoOpScope
import io.micrometer.context.ContextRegistry
import io.micrometer.context.ContextSnapshotFactory
import org.springframework.util.ClassUtils
import reactor.util.context.ContextView

/**
 * Restores the CALLER's thread-locals from the Reactor Context around an emission (ADR-0010). The
 * thread that completes a reactive exchange - an event-loop thread, mostly - carries none of the
 * caller's MDC; the Reactor Context the caller subscribed with does carry whatever the host (or the
 * sibling project limesium, for its `endpoint_*` keys) put there for exactly this purpose. The
 * `ContextView` is captured at subscription in the filter and travels with the exchange; this restorer
 * turns it back into thread-locals for the duration of the single log statement, through the
 * `ThreadLocalAccessor`s the host registered with Micrometer's context propagation - the module
 * interprets nothing itself.
 *
 * The RestClient twin restores from a thread snapshot instead (its `CallerMdcSnapshot`, ADR-0011): the
 * same layering - the caller's context outside, the module's own scope inside - with a different
 * source, because on the blocking stack the thread IS the caller's context and a Reactor Context does
 * not exist. A deliberate stack difference, like `cancelled` (ADR-0010).
 *
 * Purely ADDITIVE, like the [eu.inqudium.legatium.common.MdcScope] that follows it: only values the
 * context holds are installed, a thread-local the context does not mention stays as the emitting thread
 * has it, and the returned scope restores every touched value on close. The trace keys are owned by the
 * scope opened inside this one, so a bridge's ids the accessors restore never outrank the header's.
 */
internal fun interface AmbientContextRestorer {
    /** Installs the thread-locals [ambient] holds; the returned scope restores the previous values. */
    fun restore(ambient: ContextView): AutoCloseable

    companion object {
        private const val SNAPSHOT_FACTORY = "io.micrometer.context.ContextSnapshotFactory"

        /** Restores nothing: the choice without `io.micrometer:context-propagation` on the classpath. */
        val NONE: AmbientContextRestorer = AmbientContextRestorer { NoOpScope }

        /**
         * The restorer for this classpath: the context-propagation one when the optional library is
         * present, [NONE] otherwise. Presence is the opt-in - no `adapter-logging.*` key exists for it,
         * which keeps the configuration identical to the RestClient twin's.
         *
         * Present on BOTH loaders: the one the caller decides against ([classLoader] - the context's,
         * through which the host's optional dependencies are visible and which a test can narrow) and
         * the one [ContextPropagationRestorer]'s Micrometer references actually LINK through
         * ([linkedThrough], the defining loader of this module's classes). A shared-lib deployment that
         * puts the module in a parent loader and the library in the child alone would pass the first
         * check and fail the construction with a `NoClassDefFoundError` at context start - against the
         * fail-open promise; it gets [NONE]. The second parameter is a test seam.
         */
        fun detect(
            classLoader: ClassLoader? = AmbientContextRestorer::class.java.classLoader,
            linkedThrough: ClassLoader? = AmbientContextRestorer::class.java.classLoader,
        ): AmbientContextRestorer =
            if (ClassUtils.isPresent(SNAPSHOT_FACTORY, classLoader) && ClassUtils.isPresent(SNAPSHOT_FACTORY, linkedThrough)) {
                ContextPropagationRestorer()
            } else {
                NONE
            }
    }
}

/**
 * The context-propagation implementation - its own class, so the Micrometer types are resolved only
 * when [AmbientContextRestorer.detect] found them on the classpath. Reactor registers the
 * `ContextAccessor` that reads a `ContextView` (a service-loader entry of reactor-core), the host
 * registers the `ThreadLocalAccessor`s; the factory captures the accessors' values from the context
 * and installs them.
 */
internal class ContextPropagationRestorer(
    registry: ContextRegistry = ContextRegistry.getInstance(),
) : AmbientContextRestorer {
    private val factory =
        ContextSnapshotFactory
            .builder()
            .contextRegistry(registry)
            // Off on purpose: the module never resets a thread-local the context does not mention (a
            // caller's observation scope on a synchronously completing call, for one) - ADR-0010 "Additive".
            .clearMissing(false)
            .build()

    override fun restore(ambient: ContextView): AutoCloseable = factory.setThreadLocalsFrom<Any>(ambient)
}
