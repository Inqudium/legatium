package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition

/**
 * The observation line of the wiring report: three renderings against a bean factory. The observation
 * and tracing classes are not on this module's classpath, so the test stands in classes of its own for
 * them - the rendering only asks "does the context hold a bean of that class".
 */
class ClientObservationWiringTest {
    private val customizers = mapOf(BUILDER_CUSTOMIZER to "RestClient.Builder", TEMPLATE_CUSTOMIZER to "RestTemplate")

    @Test
    fun `should report observation with tracing when a customizer and a tracer are defined`() {
        // What is tested: describe over a factory defining one observation customizer and the tracer.
        // Success criteria: the traced line, naming only the builder whose customizer is defined,
        //   the trace-id-is-request-id consequence and no correlation header.
        // Why it matters: this is the line that tells an operator why adapter_request_id looks like a
        //   trace id and why the peer sees no X-Correlation-Id.
        // Given
        val factory = DefaultListableBeanFactory()
        factory.registerBeanDefinition("observation", RootBeanDefinition(Class.forName(BUILDER_CUSTOMIZER)))
        factory.registerBeanDefinition("tracer", RootBeanDefinition(Class.forName(TRACER)))

        // When
        val line = ClientObservationWiring.describe(factory, customizers, tracerClass = TRACER)

        // Then
        assertThat(line)
            .startsWith("Adapter logging found Boot's client observation with Micrometer Tracing wired for RestClient.Builder - ")
            .contains("its trace id is the request id and no X-Correlation-Id is generated")
            .doesNotContain("RestTemplate")
    }

    @Test
    fun `should report observation without tracing when no tracer is defined`() {
        // What is tested: describe over a factory defining both customizers and no tracer.
        // Success criteria: the observed-not-traced line naming both builders, and the generated-id
        //   consequence.
        // Why it matters: observation without a tracing bridge measures but injects no traceparent -
        //   the identity contract is the traceless one, which the line must say.
        // Given
        val factory = DefaultListableBeanFactory()
        factory.registerBeanDefinition("builderObservation", RootBeanDefinition(Class.forName(BUILDER_CUSTOMIZER)))
        factory.registerBeanDefinition("templateObservation", RootBeanDefinition(Class.forName(TEMPLATE_CUSTOMIZER)))

        // When
        val line = ClientObservationWiring.describe(factory, customizers, tracerClass = TRACER)

        // Then
        assertThat(line)
            .startsWith("Adapter logging found Boot's client observation wired for RestClient.Builder and RestTemplate but no Micrometer Tracing - ")
            .contains("the module generates the request id and sends X-Correlation-Id")
    }

    @Test
    fun `should report no observation when no customizer is defined, whatever the tracer`() {
        // What is tested: describe over a factory with a tracer but no observation customizer, and
        //   over one whose tracer class is not on the classpath at all.
        // Success criteria: both render the no-observation line naming every builder the twin could
        //   observe; a missing class counts as a missing bean, no exception.
        // Why it matters: a tracer without the client observation injects nothing - and the common
        //   module must not fail on a classpath without the optional libraries.
        // Given
        val withTracerOnly = DefaultListableBeanFactory()
        withTracerOnly.registerBeanDefinition("tracer", RootBeanDefinition(Class.forName(TRACER)))

        // When
        val line = ClientObservationWiring.describe(withTracerOnly, customizers, tracerClass = TRACER)
        val withoutClasses = ClientObservationWiring.describe(DefaultListableBeanFactory(), mapOf("no.such.Customizer" to "RestClient.Builder"), tracerClass = "no.such.Tracer")

        // Then
        assertThat(line)
            .startsWith("Adapter logging found no client observation - Boot's observation auto-configuration for RestClient.Builder and RestTemplate is not active ")
            .contains("the module generates the request id and sends X-Correlation-Id")
        assertThat(withoutClasses).startsWith("Adapter logging found no client observation - ")
    }

    private companion object {
        /** Stand-ins from this module's classpath for the observation customizers and the tracer. */
        const val BUILDER_CUSTOMIZER = "java.lang.StringBuilder"
        const val TEMPLATE_CUSTOMIZER = "java.lang.StringBuffer"
        const val TRACER = "java.lang.Thread"
    }
}
