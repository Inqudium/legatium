package eu.inqudium.legatium.webclient.logging

import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.ClientStack
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Literal pins of the twin contract this stack OWNS: the outcome vocabulary it pre-registers and the
 * message text of the arrival and exchange lines, which its own emitter renders. The contracts shared
 * with the blocking twin - meter names, MDC keys, read states, the outcome literals, the masking
 * fingerprint - live once in legatium-common and are pinned there (`SharedContractTest`,
 * `HeaderValueMaskerTest`); the packaging of the shared classes into this jar is verified by the
 * consumer smoke build (`consumer-smoke/`).
 */
class TwinContractTest {
    @Test
    fun `should pin this stack's client tag and outcome vocabulary`() {
        // What is tested: the ClientStack.WEBCLIENT facts the shared metrics owner is parameterised
        //   with - the client tag of the gauge and the outcomes pre-registered on the events counter.
        // Success criteria: client=webclient, and exactly success, rejected, failure, timeout and
        //   cancelled in this order - the shared four plus the reactive disposition.
        // Why it matters: alerts on adapter.logging.events{outcome="cancelled"} must find the value at
        //   zero from the start; a lost cancelled would silently empty the abandoned-call signal.
        // Given/When/Then
        assertThat(ClientStack.WEBCLIENT.tagValue).isEqualTo("webclient")
        assertThat(ClientStack.WEBCLIENT.outcomes.map { it.tagValue }).containsExactly("success", "rejected", "failure", "timeout", "cancelled")
    }

    @Test
    fun `should pin the exchange and arrival message format to the literal twin contract`() {
        // What is tested: the MESSAGE half of the twin contract - the field names are locked by
        //   ClientLogFieldTest, the message text is pinned here in both twins.
        // Success criteria: a pinned filter renders the literal messages both twins ship - the target
        //   names an unnamed client's call, the client's name a named one's (ADR-0009).
        // Why it matters: plain-text appenders and the README's parity promise key on this text; a
        //   divergence in one twin would otherwise ship silently.
        // Given
        val properties = ClientLoggingProperties(loggerName = "adapter-http-exchange-reactive-twin-message-test", logRequestStart = true)
        val filter = filterWith(properties, AtomicLong())
        val log = CapturedLogger(properties.loggerName)
        try {
            // When: one successful call of an unnamed client, one of a named client
            filter.filter(request(uri = "https://api.example.com/things"), answering()).flatMap { it.releaseBody() }.block()
            val named =
                request(uri = "https://api.example.com/things") {
                    attribute(ClientRequestLoggingFilter.ADAPTER_NAME_ATTRIBUTE, "things")
                }
            filter.filter(named, answering()).flatMap { it.releaseBody() }.block()

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
