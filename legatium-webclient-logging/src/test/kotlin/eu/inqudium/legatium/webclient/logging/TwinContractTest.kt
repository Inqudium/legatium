package eu.inqudium.legatium.webclient.logging

import eu.inqudium.legatium.common.BodyReadState
import eu.inqudium.legatium.common.HeaderLogProperties
import eu.inqudium.legatium.common.MdcKeys
import eu.inqudium.legatium.common.TraceMdcKeys
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Literal pins of the contracts DUPLICATED across the client-logging twins that the cross-module
 * lockstep tests do not cover: meter names, MDC key values, the masking fingerprint, and the message
 * text of the arrival and exchange lines. Both twins carry this test with the identical literals - a
 * drift in either module breaks that module's build.
 */
class TwinContractTest {
    @Test
    fun `should pin the meter names to the literal twin contract`() {
        // Given/When/Then
        assertThat(ClientLoggingMetrics.FAIL_OPEN_METER).isEqualTo("client.logging.failopen")
        assertThat(ClientLoggingMetrics.EVENTS_METER).isEqualTo("client.logging.events")
        assertThat(ClientLoggingMetrics.OPEN_EXCHANGES_METER).isEqualTo("client.logging.exchanges.open")
        assertThat(ClientLoggingMetrics.CORRELATION_METER).isEqualTo("client.logging.correlation.id")
        assertThat(ClientLoggingMetrics.REQUEST_BODY_SIZE_METER).isEqualTo("client.request.body.size")
        assertThat(ClientLoggingMetrics.RESPONSE_BODY_SIZE_METER).isEqualTo("client.response.body.size")
        assertThat(ClientLoggingMetrics.RESPONSE_BODY_READ_METER).isEqualTo("client.response.body.read")
        assertThat(ClientLoggingMetrics.UNTEMPLATED_URI).isEqualTo("UNKNOWN")
        assertThat(ClientLoggingMetrics.UNKNOWN_HOST).isEqualTo("UNKNOWN")
    }

    @Test
    fun `should pin the response body read states to the literal twin contract`() {
        // Given/When/Then
        assertThat(BodyReadState.UNREAD.tagValue).isEqualTo("unread")
        assertThat(BodyReadState.PARTIAL.tagValue).isEqualTo("partial")
        assertThat(BodyReadState.COMPLETE.tagValue).isEqualTo("complete")
        assertThat(BodyReadState.entries).hasSize(3)
    }

    @Test
    fun `should pin the MDC keys to the literal twin contract`() {
        // Given/When/Then
        assertThat(MdcKeys.REQUEST_ID).isEqualTo("client_request_id")
        assertThat(MdcKeys.REQUEST_METHOD).isEqualTo("client_method")
        assertThat(MdcKeys.ROUTE).isEqualTo("client_route")
        assertThat(TraceMdcKeys.TRACE_ID).isEqualTo("traceId")
        assertThat(TraceMdcKeys.SPAN_ID).isEqualTo("spanId")
    }

    @Test
    fun `should pin the masking fingerprint format to the literal twin contract`() {
        // Given/When/Then
        assertThat(HeaderLogProperties.mask("secret-token")).isEqualTo("12:930bbdc51b6aed5c")
    }

    @Test
    fun `should pin the shared outcome vocabulary plus this stack's own disposition`() {
        // Given/When/Then: the shared three, plus the reactive `cancelled`
        assertThat(ClientLoggingMetrics.OUTCOME_SUCCESS).isEqualTo("success")
        assertThat(ClientLoggingMetrics.OUTCOME_FAILURE).isEqualTo("failure")
        assertThat(ClientLoggingMetrics.OUTCOME_TIMEOUT).isEqualTo("timeout")
        assertThat(ClientLoggingMetrics.OUTCOME_CANCELLED).isEqualTo("cancelled")
    }

    @Test
    fun `should pin the exchange and arrival message format to the literal twin contract`() {
        // What is tested: the MESSAGE half of the twin contract.
        // Success criteria: a pinned filter renders the literal messages both twins ship.
        // Why it matters: plain-text appenders and the README's parity promise key on this text.
        // Given
        val properties = ClientLoggingProperties(loggerName = "http-client-exchange-reactive-twin-message-test", logRequestStart = true)
        val filter = ClientRequestLoggingFilter(properties, { 0L }, { "generated-42" }, SimpleMeterRegistry())
        val log = CapturedLogger(properties.loggerName)
        try {
            // When
            filter.filter(request(uri = "https://api.example.com/things"), answering()).flatMap { it.releaseBody() }.block()

            // Then
            assertThat(log.events.map { it.formattedMessage })
                .containsExactly(
                    "Client http exchange started GET https://api.example.com/things [client_request_id=generated-42]",
                    "Client http exchange GET https://api.example.com/things -> 200 [client_request_id=generated-42]",
                )
        } finally {
            log.detach()
        }
    }
}
