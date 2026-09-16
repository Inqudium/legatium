package eu.inqudium.legatium.webclient.logging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import reactor.util.context.Context

/**
 * The restorer of the caller's context (ADR-0010): detection by classpath, the additive install-and-restore
 * contract of the context-propagation implementation, and the no-op of the fallback.
 */
class AmbientContextRestorerTest {
    private val key = "endpoint_request_id"
    private val accessor = MdcAccessorGuard(key)

    @BeforeEach
    fun clearMdc() {
        MDC.clear()
    }

    @AfterEach
    fun tearDown() {
        MDC.clear()
        accessor.close()
    }

    @Test
    fun `should detect the context-propagation restorer on this classpath and fall back to none without it`() {
        // What is tested: AmbientContextRestorer.detect - the classpath opt-in.
        // Success criteria: with io.micrometer:context-propagation present (this test classpath) the
        //   context-propagation implementation; against a class loader that cannot see it, NONE.
        // Why it matters: the library is optional; a host without it must get the pre-ADR-0010
        //   behaviour, never a NoClassDefFoundError from the emitter.
        // Given / When / Then
        assertThat(AmbientContextRestorer.detect()).isInstanceOf(ContextPropagationRestorer::class.java)
        assertThat(AmbientContextRestorer.detect(object : ClassLoader(null) {})).isSameAs(AmbientContextRestorer.NONE)
    }

    @Test
    fun `should install the context's values through the registered accessors and restore the previous ones on close`() {
        // What is tested: ContextPropagationRestorer.restore - the value a Reactor Context holds under
        //   an accessor's key lands in the thread-local for the scope's lifetime.
        // Success criteria: inside the scope the MDC carries the context's value; after close it carries
        //   the value the thread had before.
        // Why it matters: this is the join of the client line to the server line on a thread that never
        //   ran the server request; and a pooled thread must be left exactly as found.
        // Given: the emitting thread carries a stale value, the context the caller's
        MDC.put(key, "stale-on-thread")
        val restorer = ContextPropagationRestorer()

        // When / Then: the context's value for the scope, the thread's own afterwards
        restorer.restore(Context.of(key, "caller-42")).use {
            assertThat(MDC.get(key)).isEqualTo("caller-42")
        }
        assertThat(MDC.get(key)).isEqualTo("stale-on-thread")
    }

    @Test
    fun `should leave a thread-local the context does not mention as the emitting thread has it`() {
        // What is tested: the ADDITIVE contract - clearMissing stays off.
        // Success criteria: a context without the key installs nothing and resets nothing; the
        //   thread's value is visible inside the scope and unchanged after it.
        // Why it matters: on a synchronously completing call the emitting thread IS the caller's, and
        //   its MDC (an inbound identity, an observation scope) must not be reset by a restorer that
        //   only knows what the Reactor Context holds.
        // Given
        MDC.put(key, "on-thread")

        // When / Then
        ContextPropagationRestorer().restore(Context.empty()).use {
            assertThat(MDC.get(key)).isEqualTo("on-thread")
        }
        assertThat(MDC.get(key)).isEqualTo("on-thread")
    }

    @Test
    fun `should restore nothing without the library`() {
        // What is tested: AmbientContextRestorer.NONE.
        // Success criteria: a context holding a value under a registered key changes nothing on the
        //   thread, inside or after the scope.
        // Why it matters: NONE is the whole behaviour of a host without context-propagation - it must be
        //   a true no-op, not a partial implementation.
        // Given / When / Then
        AmbientContextRestorer.NONE.restore(Context.of(key, "caller-42")).use {
            assertThat(MDC.get(key)).isNull()
        }
        assertThat(MDC.get(key)).isNull()
    }
}
