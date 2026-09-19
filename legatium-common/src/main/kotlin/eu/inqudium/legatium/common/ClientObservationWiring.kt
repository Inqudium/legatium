package eu.inqudium.legatium.common

import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.util.ClassUtils

/**
 * The one line of the auto-configurations' wiring report that says whether Boot's CLIENT OBSERVATION is
 * wired next to the module - one implementation for both twins.
 *
 * The module is observationally neutral (ADR-0002): it never opens a span and never injects a
 * `traceparent`. Whether a call goes out traced is decided by Boot's observation customizer for the
 * builder (`ObservationRestClientCustomizer`, `ObservationRestTemplateCustomizer`,
 * `ObservationWebClientCustomizer` - registered only with an `ObservationRegistry` bean) together with
 * Micrometer Tracing's handler (registered only with a `Tracer` bean), which injects the header when the
 * observation starts. That decision has no `adapter-logging.*` key and was so far readable nowhere at
 * startup, although it flips the identity contract: a traced call takes the trace id as its request id
 * and gets NO correlation header, a traceless one gets a generated id and the header.
 *
 * [describe] asks the context for beans of the customizer and tracer classes and renders one of three
 * lines: observation with tracing, observation without tracing, no observation. The twins call it once
 * every singleton exists (a `SmartInitializingSingleton`): Boot declares the RestClient customizer under
 * its interface type, so only the INSTANCE reveals it. It reports the wiring at context start, not the
 * fate of a call: a host can still switch the observation off per client, filter it with an
 * `ObservationPredicate` or build a client by hand. The exchange line itself stays the per-call truth.
 *
 * The classes are named as strings, so the common module compiles without the optional observation
 * and tracing libraries; a class that is not on the classpath counts as "no such bean".
 */
internal object ClientObservationWiring {
    /** Micrometer Tracing's facade; its bean is the sign that a tracing bridge is configured. */
    const val TRACER = "io.micrometer.tracing.Tracer"

    /**
     * One line for [customizers] - Boot's observation customizer classes mapped to the builder they
     * observe, in the twin's wording ("RestClient.Builder") - against the beans of [beanFactory].
     * [tracerClass] is [TRACER]; the tests inject a class of their own classpath.
     */
    fun describe(
        beanFactory: ListableBeanFactory,
        customizers: Map<String, String>,
        tracerClass: String = TRACER,
    ): String {
        val observed = customizers.filterKeys { hasBean(beanFactory, it) }.values.joinToString(" and ")
        return when {
            observed.isEmpty() -> noObservation(customizers.values.joinToString(" and "))
            hasBean(beanFactory, tracerClass) -> observedAndTraced(observed)
            else -> observedNotTraced(observed)
        }
    }

    private fun noObservation(builders: String): String =
        "Adapter logging found no client observation - Boot's observation auto-configuration for $builders is not active " +
            "(no ObservationRegistry bean, or the observation module is absent); the module generates the request id and " +
            "sends X-Correlation-Id on every call that carries no traceparent"

    private fun observedAndTraced(builders: String): String =
        "Adapter logging found Boot's client observation with Micrometer Tracing wired for $builders - every call built there " +
            "goes out with a traceparent, its trace id is the request id and no X-Correlation-Id is generated"

    private fun observedNotTraced(builders: String): String =
        "Adapter logging found Boot's client observation wired for $builders but no Micrometer Tracing - calls are observed, " +
            "not traced, so the module generates the request id and sends X-Correlation-Id on every call that carries no traceparent"

    /** Whether the context holds a bean of [className] - matched by instance once the singletons exist. */
    private fun hasBean(
        beanFactory: ListableBeanFactory,
        className: String,
    ): Boolean {
        val classLoader = (beanFactory as? ConfigurableBeanFactory)?.beanClassLoader ?: ClientObservationWiring::class.java.classLoader
        if (!ClassUtils.isPresent(className, classLoader)) {
            return false
        }
        return beanFactory.getBeanNamesForType(ClassUtils.forName(className, classLoader), true, true).isNotEmpty()
    }
}
