package eu.inqudium.legatium.restclient.logging

import ch.qos.logback.classic.Level
import eu.inqudium.legatium.common.CapturedLogger
import eu.inqudium.legatium.common.ClientLoggingMetrics
import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.ClientStack
import eu.inqudium.legatium.common.keyValues
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.net.SocketException
import java.util.concurrent.atomic.AtomicLong

/**
 * Literal pins of the twin contract this stack OWNS: its client tag, the disposition it writes for an
 * exchange the caller abandoned, and the message text of the arrival and exchange lines, which its own
 * emitter renders. The contracts shared
 * with the reactive twin - meter names, MDC keys, read states, the outcome literals, the masking
 * fingerprint - live once in legatium-common and are pinned there (`SharedContractTest`,
 * `HeaderValueMaskerTest`); the packaging of the shared classes into this jar is verified by the
 * consumer smoke build (`consumer-smoke/`).
 */
class TwinContractTest {
    @Test
    fun `should pin this stack's client tag and outcome vocabulary`() {
        // What is tested: the ClientStack.RESTCLIENT fact the shared metrics owner is parameterised with
        //   - the client tag of the gauge - and the outcomes the owner pre-registers on the events counter.
        // Success criteria: client=restclient, and exactly success, rejected, failure, timeout and
        //   cancelled - the reactive twin's vocabulary, since this stack logs cancelled too (ADR-0015).
        // Why it matters: alerts on adapter.logging.events{outcome=...} for this stack must find every
        //   value at zero from the start, cancelled included.
        // Given
        val registry = SimpleMeterRegistry()

        // When
        ClientLoggingMetrics.forRegistry(registry, ClientStack.RESTCLIENT)

        // Then
        assertThat(ClientStack.RESTCLIENT.tagValue).isEqualTo("restclient")
        assertThat(registry.get(ClientLoggingMetrics.EVENTS_METER).counters().map { it.id.getTag("outcome") })
            .containsExactlyInAnyOrder("success", "rejected", "failure", "timeout", "cancelled")
    }

    @Test
    fun `should log an exchange the caller abandoned as WARN cancelled without a cause`() {
        // What is tested: the disposition half of the twin contract for "the caller abandoned the
        //   exchange" - here a caller interrupted while the wire call blocked, which the JDK answers
        //   by closing the socket.
        // Success criteria: exactly one event, WARN, adapter_outcome=cancelled, `-> -`, no status, no
        //   throwable - literally what the reactive twin writes for a cancelled subscription.
        // Why it matters: an alert keyed on adapter_outcome == "cancelled" must fire for both twins,
        //   and neither may file the caller's departure under ERROR (ADR-0015).
        // Given
        val properties = ClientLoggingProperties(loggerName = "adapter-http-exchange-twin-cancel-test")
        val interceptor = interceptorWith(properties, AtomicLong())
        val log = CapturedLogger(properties.loggerName)
        try {
            // When: the wire call fails on an interrupted thread; the flag is this test's to clear
            catchThrowable {
                interceptor.intercept(request(uri = "https://api.example.com/things"), ByteArray(0)) { _, _ ->
                    Thread.currentThread().interrupt()
                    throw SocketException("Closed by interrupt")
                }
            }
            Thread.interrupted()

            // Then
            val event = log.events.single()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(event.formattedMessage).isEqualTo("Adapter http exchange GET https://api.example.com/things -> - [adapter_request_id=generated-42]")
            assertThat(keyValues(event)).containsEntry("adapter_outcome", "cancelled").doesNotContainKey("adapter_response_status_code")
            assertThat(event.throwableProxy).isNull()
        } finally {
            Thread.interrupted()
            log.detach()
        }
    }

    @Test
    fun `should pin the exchange and arrival message format to the literal twin contract`() {
        // What is tested: the MESSAGE half of the twin contract - the field names are locked by
        //   ClientLogFieldTest, the message text is pinned here in both twins.
        // Success criteria: a pinned interceptor renders the literal messages both twins ship - the
        //   target names an unnamed client's call, the client's name a named one's (ADR-0009).
        // Why it matters: plain-text appenders and the README's parity promise key on this text; a
        //   divergence in one twin would otherwise ship silently.
        // Given
        val properties = ClientLoggingProperties(loggerName = "adapter-http-exchange-twin-message-test", logRequestStart = true)
        val interceptor = interceptorWith(properties, AtomicLong())
        val log = CapturedLogger(properties.loggerName)
        try {
            // When: one successful call of an unnamed client, one of a named client
            interceptor.intercept(request(uri = "https://api.example.com/things"), ByteArray(0), answering()).consumeAndClose()
            val named = request(uri = "https://api.example.com/things")
            named.attributes[ClientRequestLoggingInterceptor.ADAPTER_NAME_ATTRIBUTE] = "things"
            interceptor.intercept(named, ByteArray(0), answering()).consumeAndClose()

            // Then: the literal messages, identical in both twins
            assertThat(log.events.map { it.formattedMessage })
                .containsExactly(
                    "Adapter http exchange started GET https://api.example.com/things [adapter_request_id=generated-42]",
                    "Adapter http exchange GET https://api.example.com/things -> 200 [adapter_request_id=generated-42]",
                    "Adapter http exchange started GET things [adapter_request_id=generated-42]",
                    "Adapter http exchange GET things -> 200 [adapter_request_id=generated-42]",
                )
        } finally {
            log.detach()
        }
    }
}
