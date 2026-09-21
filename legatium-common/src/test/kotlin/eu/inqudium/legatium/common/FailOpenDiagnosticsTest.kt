package eu.inqudium.legatium.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The shared fail-open guard shape: interrupt-flag restoration, the confined diagnostics channel, and
 * the one wiring report every `stage=wiring` guard of both twins goes through - level, stack trace
 * and counter per [WiringCost], pinned here once instead of at the thirteen callers.
 */
class FailOpenDiagnosticsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = ClientLoggingMetrics.forRegistry(registry, ClientStack.RESTCLIENT)
    private val breadcrumbs = CapturedLogger(BREADCRUMB_LOGGER, Level.DEBUG)

    @AfterEach
    fun clearInterrupt() {
        Thread.interrupted()
        breadcrumbs.detach()
    }

    @ParameterizedTest
    @EnumSource(WiringCost::class)
    internal fun `should count stage wiring and log the breadcrumb at the level and with the stack trace the cost decides`(cost: WiringCost) {
        // What is tested: reportWiringFailure for every WiringCost - the counter, the level, the message
        //   with its placeholders and the exception's toString appended, and whether the event carries
        //   the exception as its cause (the stack trace), all decided by the cost alone.
        // Success criteria: stage=wiring at 1; exactly one event on the breadcrumb logger at
        //   cost.level, formatted "<sentence>: <exception>"; the event's cause is the exception iff
        //   cost.withStackTrace.
        // Why it matters: thirteen guards in both twins name a cost and nothing else; the twins' tests
        //   read levels and sentences but not the stack trace, so a regression of the rule - a lost
        //   trace on a failed feature, a trace on every degraded event - would pass them.
        // Given
        val failure = IllegalStateException("adapter refused")

        // When
        reportWiringFailure(metrics, breadcrumbs.logger, cost, failure, "Feature lost for {} {}", "GET", "/things")

        // Then
        assertThat(registry.count(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "wiring")).isEqualTo(1.0)
        val event = breadcrumbs.events.single()
        assertThat(event.level).isEqualTo(Level.toLevel(cost.level.name))
        assertThat(event.formattedMessage).isEqualTo("Feature lost for GET /things: java.lang.IllegalStateException: adapter refused")
        if (cost.withStackTrace) {
            assertThat(event.throwableProxy?.message).isEqualTo("adapter refused")
        } else {
            assertThat(event.throwableProxy).isNull()
        }
    }

    @Test
    fun `should pin the level and stack trace of each cost`() {
        // What is tested: the rule itself - which cost errors, which warns, which carries the trace.
        // Success criteria: LOST_FEATURE is ERROR with the trace, DIRTY_TEARDOWN WARN with the trace,
        //   DEGRADED_EVENT WARN without it, and there is no fourth cost.
        // Why it matters: the enum IS the documented rule (common guide, "Fail-open contract"); a
        //   changed flag would silently change every breadcrumb of that cost in both twins.
        // Given/When/Then
        assertThat(WiringCost.entries).containsExactly(WiringCost.LOST_FEATURE, WiringCost.DIRTY_TEARDOWN, WiringCost.DEGRADED_EVENT)
        assertThat(WiringCost.LOST_FEATURE.level).isEqualTo(org.slf4j.event.Level.ERROR)
        assertThat(WiringCost.LOST_FEATURE.withStackTrace).isTrue()
        assertThat(WiringCost.DIRTY_TEARDOWN.level).isEqualTo(org.slf4j.event.Level.WARN)
        assertThat(WiringCost.DIRTY_TEARDOWN.withStackTrace).isTrue()
        assertThat(WiringCost.DEGRADED_EVENT.level).isEqualTo(org.slf4j.event.Level.WARN)
        assertThat(WiringCost.DEGRADED_EVENT.withStackTrace).isFalse()
    }

    @Test
    fun `should still count the failure and let nothing escape when the breadcrumb appender throws`() {
        // What is tested: the wiring report under a broken diagnostics channel - the counter runs before
        //   the line, and the throwing appender is confined by reportQuietly.
        // Success criteria: no exception reaches the caller; stage=wiring is at 1.
        // Why it matters: the report runs inside catch blocks of the call path; an escaping appender
        //   failure would fail the call the guard exists to protect.
        // Given
        val throwing =
            object : AppenderBase<ILoggingEvent>() {
                override fun append(event: ILoggingEvent) = error("appender broken")
            }.apply { start() }
        breadcrumbs.logger.addAppender(throwing)

        // When/Then
        try {
            assertThatCode {
                reportWiringFailure(metrics, breadcrumbs.logger, WiringCost.LOST_FEATURE, IllegalStateException("x"), "Feature lost")
            }.doesNotThrowAnyException()
        } finally {
            breadcrumbs.logger.detachAppender(throwing)
        }
        assertThat(registry.count(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "wiring")).isEqualTo(1.0)
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

    private companion object {
        val BREADCRUMB_LOGGER: String = LoggerFactory.getLogger(FailOpenDiagnosticsTest::class.java).name
    }
}
