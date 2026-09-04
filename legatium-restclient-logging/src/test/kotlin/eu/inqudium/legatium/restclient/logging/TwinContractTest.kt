package eu.inqudium.legatium.restclient.logging

import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.ClientStack
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Literal pins of the twin contract this stack OWNS: the outcome vocabulary it pre-registers and the
 * message text of the arrival and exchange lines, which its own emitter renders. The contracts shared
 * with the reactive twin - meter names, MDC keys, read states, the outcome literals, the masking
 * fingerprint - live once in legatium-common and are pinned there (`SharedContractTest`,
 * `HeaderValueMaskerTest`); the packaging of the shared classes into this jar is verified by the
 * consumer smoke build (`consumer-smoke/`).
 */
class TwinContractTest {
    @Test
    fun `should pin this stack's client tag and outcome vocabulary`() {
        // What is tested: the ClientStack.RESTCLIENT facts the shared metrics owner is parameterised
        //   with - the client tag of the gauge and the outcomes pre-registered on the events counter.
        // Success criteria: client=restclient, and exactly success, failure and timeout in this order;
        //   cancelled belongs to the reactive twin alone.
        // Why it matters: alerts on adapter.logging.events{outcome=...} for this stack must find every
        //   value at zero from the start, and none the blocking stack can never produce.
        // Given/When/Then
        assertThat(ClientStack.RESTCLIENT.tag).isEqualTo("restclient")
        assertThat(ClientStack.RESTCLIENT.outcomes.map { it.tagValue }).containsExactly("success", "failure", "timeout")
    }

    @Test
    fun `should pin the exchange and arrival message format to the literal twin contract`() {
        // What is tested: the MESSAGE half of the twin contract - the field names are locked by
        //   ClientLogFieldTest, the message text is pinned here in both twins.
        // Success criteria: a pinned interceptor renders the literal messages both twins ship.
        // Why it matters: plain-text appenders and the README's parity promise key on this text; a
        //   divergence in one twin would otherwise ship silently.
        // Given
        val properties = ClientLoggingProperties(loggerName = "http-adapter-exchange-twin-message-test", logRequestStart = true)
        val interceptor = ClientRequestLoggingInterceptor(properties, { 0L }, { "generated-42" }, SimpleMeterRegistry())
        val log = CapturedLogger(properties.loggerName)
        try {
            // When: one successful call
            interceptor.intercept(request(uri = "https://api.example.com/things"), ByteArray(0), answering()).consumeAndClose()

            // Then: the literal messages, identical in both twins
            assertThat(log.events.map { it.formattedMessage })
                .containsExactly(
                    "Adapter http exchange started GET https://api.example.com/things [adapter_request_id=generated-42]",
                    "Adapter http exchange GET https://api.example.com/things -> 200 [adapter_request_id=generated-42]",
                )
        } finally {
            log.detach()
        }
    }
}
