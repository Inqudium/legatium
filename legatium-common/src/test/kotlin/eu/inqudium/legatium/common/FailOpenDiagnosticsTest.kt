package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The shared fail-open guard shape: interrupt-flag restoration and the confined diagnostics channel. */
class FailOpenDiagnosticsTest {
    @AfterEach
    fun clearInterrupt() {
        Thread.interrupted()
    }

    @Test
    fun `should restore the interrupt flag and route an InterruptedException to its handler`() {
        // What is tested: the InterruptedException branch of failOpen - the JVM cleared the flag when it
        //   threw, and on a request-serving or event-loop thread the interrupt must still reach its
        //   addressee after the guard confined the exception.
        // Success criteria: the thread is interrupted afterwards, the interrupted handler ran once, the
        //   generic handler did not, nothing escaped.
        // Why it matters: a swallowed interrupt on a pooled thread is a hang or a late cancellation in the
        //   host - the one outcome a fail-open logging guard must not produce.
        // Given
        val interruptedSeen = AtomicInteger()
        val failureSeen = AtomicInteger()

        // When
        failOpen(
            onInterrupted = { interruptedSeen.incrementAndGet() },
            onFailure = { failureSeen.incrementAndGet() },
        ) { throw InterruptedException("stop") }

        // Then
        assertThat(Thread.currentThread().isInterrupted).isTrue()
        assertThat(interruptedSeen).hasValue(1)
        assertThat(failureSeen).hasValue(0)
    }

    @Test
    fun `should confine a handler that throws instead of letting it escape the guard`() {
        // What is tested: the diagnostics channel itself is guarded - a handler backed by a throwing
        //   counter or appender must not turn a confined failure into an escaping one.
        // Success criteria: neither the failing handler nor the original exception escapes; the interrupt
        //   flag is still restored when the interrupted handler throws.
        // Why it matters: the handler runs against host-provided components; their failure is the case
        //   reportQuietly exists for.
        // Given/When
        failOpen(onInterrupted = {}, onFailure = { error("counter broken") }) { throw IllegalArgumentException("boom") }
        failOpen(onInterrupted = { error("counter broken") }, onFailure = {}) { throw InterruptedException("stop") }

        // Then
        assertThat(Thread.currentThread().isInterrupted).isTrue()
    }

    @Test
    fun `should run the operation untouched when it does not throw`() {
        // What is tested: the happy path of failOpen - no catch branch runs when the operation returns.
        // Success criteria: the operation ran, neither handler was called - COUNTED, because the
        //   guard reports the handlers quietly and a throwing handler could not fail the test - and
        //   the interrupt flag stays clear.
        // Why it matters: the guard wraps every emitter and callback; touching the interrupt flag or
        //   reporting on success would count a fail-open event for every healthy call.
        // Given/When
        val ran = AtomicBoolean()
        val handled = AtomicInteger()
        failOpen(onInterrupted = { handled.incrementAndGet() }, onFailure = { handled.incrementAndGet() }) { ran.set(true) }

        // Then
        assertThat(ran).isTrue()
        assertThat(handled).hasValue(0)
        assertThat(Thread.currentThread().isInterrupted).isFalse()
    }
}
