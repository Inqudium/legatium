package eu.inqudium.legatium.webclient.logging

import eu.inqudium.legatium.common.BodyReadState
import eu.inqudium.legatium.common.ClientLoggingMetrics
import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.ClientOutcome
import eu.inqudium.legatium.common.HeaderValueMasker
import eu.inqudium.legatium.common.MdcKeys
import eu.inqudium.legatium.common.TraceMdcKeys
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Literal pins of the contracts DUPLICATED across the adapter-logging twins that the cross-module
 * lockstep tests do not cover: meter names, MDC key values, the masking fingerprint, and the message
 * text of the arrival and exchange lines. Both twins carry this test with the identical literals - a
 * drift in either module breaks that module's build.
 */
class TwinContractTest {
    @Test
    fun `should pin the meter names to the literal twin contract`() {
        // What is tested: the string constants of ClientLoggingMetrics that name the seven meters
        //   and the two fallback tag values.
        // Success criteria: each constant equals the literal the RestClient twin pins in its copy
        //   of this test.
        // Why it matters: dashboards and alerts key on these names across both twins; a rename in
        //   the shared class must break both builds, not silently split a metric.
        // Given/When/Then
        assertThat(ClientLoggingMetrics.FAIL_OPEN_METER).isEqualTo("adapter.logging.failopen")
        assertThat(ClientLoggingMetrics.EVENTS_METER).isEqualTo("adapter.logging.events")
        assertThat(ClientLoggingMetrics.OPEN_EXCHANGES_METER).isEqualTo("adapter.logging.exchanges.open")
        assertThat(ClientLoggingMetrics.CORRELATION_METER).isEqualTo("adapter.logging.correlation.id")
        assertThat(ClientLoggingMetrics.REQUEST_BODY_SIZE_METER).isEqualTo("adapter.request.body.size")
        assertThat(ClientLoggingMetrics.RESPONSE_BODY_SIZE_METER).isEqualTo("adapter.response.body.size")
        assertThat(ClientLoggingMetrics.RESPONSE_BODY_READ_METER).isEqualTo("adapter.response.body.read")
        assertThat(ClientLoggingMetrics.UNTEMPLATED_URI).isEqualTo("UNKNOWN")
        assertThat(ClientLoggingMetrics.UNKNOWN_HOST).isEqualTo("UNKNOWN")
    }

    @Test
    fun `should pin the response body read states to the literal twin contract`() {
        // What is tested: the `state` tag values of adapter.response.body.read and the size of the
        //   enum.
        // Success criteria: unread, partial, complete - and no fourth value.
        // Why it matters: the tag values are the wire contract of the counter; an added or renamed
        //   state would change the meter's cardinality under every consumer.
        // Given/When/Then
        assertThat(BodyReadState.UNREAD.tagValue).isEqualTo("unread")
        assertThat(BodyReadState.PARTIAL.tagValue).isEqualTo("partial")
        assertThat(BodyReadState.COMPLETE.tagValue).isEqualTo("complete")
        assertThat(BodyReadState.entries).hasSize(3)
    }

    @Test
    fun `should pin the MDC keys to the literal twin contract`() {
        // What is tested: the MdcKeys and TraceMdcKeys constants both twins write into the MDC.
        // Success criteria: the three adapter_* keys and Boot's traceId/spanId names are the
        //   literals.
        // Why it matters: structured encoders emit MDC entries as fields; a changed key breaks the
        //   join between the client line, the inbound line and the trace.
        // Given/When/Then
        assertThat(MdcKeys.REQUEST_ID).isEqualTo("adapter_request_id")
        assertThat(MdcKeys.REQUEST_METHOD).isEqualTo("adapter_method")
        assertThat(MdcKeys.ROUTE).isEqualTo("adapter_route")
        assertThat(TraceMdcKeys.TRACE_ID).isEqualTo("traceId")
        assertThat(TraceMdcKeys.SPAN_ID).isEqualTo("spanId")
    }

    @Test
    fun `should pin the masking fingerprint format to the literal twin contract`() {
        // What is tested: HeaderValueMasker.DEFAULT over a fixed value - the `length:hex` shape
        //   with the first 64 bits of SHA-256.
        // Success criteria: "secret-token" renders as the literal "12:930bbdc51b6aed5c".
        // Why it matters: a masked token must correlate across the RestClient line, the WebClient
        //   line and the inbound sibling; any drift in the digest or the format would break that.
        // Given/When/Then
        assertThat(HeaderValueMasker.DEFAULT.mask("secret-token")).isEqualTo("12:930bbdc51b6aed5c")
    }

    @Test
    fun `should pin the shared outcome vocabulary plus this stack's own disposition`() {
        // What is tested: the tag values of ClientOutcome - the three both twins share and the
        //   reactive `cancelled`.
        // Success criteria: the four literals match the values dashboards filter on.
        // Why it matters: adapter_outcome and the events counter's outcome tag are the closed
        //   vocabulary every alert keys on; a renamed value would silently zero an alert.
        // Given/When/Then: the shared three, plus the reactive `cancelled`
        assertThat(ClientOutcome.SUCCESS.tagValue).isEqualTo("success")
        assertThat(ClientOutcome.FAILURE.tagValue).isEqualTo("failure")
        assertThat(ClientOutcome.TIMEOUT.tagValue).isEqualTo("timeout")
        assertThat(ClientOutcome.CANCELLED.tagValue).isEqualTo("cancelled")
    }

    @Test
    fun `should pin the exchange and arrival message format to the literal twin contract`() {
        // What is tested: the MESSAGE half of the twin contract.
        // Success criteria: a pinned filter renders the literal messages both twins ship.
        // Why it matters: plain-text appenders and the README's parity promise key on this text.
        // Given
        val properties = ClientLoggingProperties(loggerName = "http-adapter-exchange-reactive-twin-message-test", logRequestStart = true)
        val filter = ClientRequestLoggingFilter(properties, { 0L }, { "generated-42" }, SimpleMeterRegistry())
        val log = CapturedLogger(properties.loggerName)
        try {
            // When
            filter.filter(request(uri = "https://api.example.com/things"), answering()).flatMap { it.releaseBody() }.block()

            // Then
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
