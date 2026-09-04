package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Literal pins of the operator-facing contracts that live ONCE in this module and ship, inlined, in both
 * twins (ADR-0003): meter names and fallback tag values, the response-body read states, the MDC keys and
 * the outcome vocabulary. Dashboards, alerts and log joins key on these strings; a rename here would
 * split every one of them across both twins silently - the pin turns it into a build failure. Spelled
 * out as literals, not derived from the constants, on purpose. The per-stack facts - which outcomes a
 * stack pre-registers, its `client` tag, the message text - stay pinned in each twin's `TwinContractTest`;
 * the masking fingerprint is pinned in `HeaderValueMaskerTest`.
 */
class SharedContractTest {
    @Test
    fun `should pin the meter names and the fallback tag values`() {
        // What is tested: the string constants of ClientLoggingMetrics that name the seven meters and
        //   the two fallback tag values.
        // Success criteria: each constant equals its literal.
        // Why it matters: dashboards and alerts key on these names across both twins and beside
        //   limesium's endpoint.* family; a rename must break the build, not silently split a metric.
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
        assertThat(ClientLoggingMetrics.CLIENT_TAG).isEqualTo("client")
    }

    @Test
    fun `should pin the response body read states`() {
        // What is tested: the `state` tag values of adapter.response.body.read and the size of the enum.
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
    fun `should pin the MDC keys`() {
        // What is tested: the MdcKeys and TraceMdcKeys constants both twins write into the MDC.
        // Success criteria: the three adapter_* keys and Boot's traceId/spanId names are the literals.
        // Why it matters: structured encoders emit MDC entries as fields by name - the adapter_ prefix
        //   keeps them beside limesium's endpoint_ keys, and only Boot's own trace key names make the
        //   join with the tracing bridge hold.
        // Given/When/Then
        assertThat(MdcKeys.REQUEST_ID).isEqualTo("adapter_request_id")
        assertThat(MdcKeys.REQUEST_METHOD).isEqualTo("adapter_method")
        assertThat(MdcKeys.ROUTE).isEqualTo("adapter_route")
        assertThat(TraceMdcKeys.TRACE_ID).isEqualTo("traceId")
        assertThat(TraceMdcKeys.SPAN_ID).isEqualTo("spanId")
    }

    @Test
    fun `should pin the outcome vocabulary`() {
        // What is tested: the tagValue literals of ClientOutcome - the three both stacks share and the
        //   reactive cancelled - and the size of the enum.
        // Success criteria: the four literals match the values dashboards filter on; no fifth value.
        // Why it matters: adapter_outcome and the outcome tag of adapter.logging.events are the closed
        //   vocabulary every alert keys on; a renamed value would silently zero an alert.
        // Given/When/Then
        assertThat(ClientOutcome.SUCCESS.tagValue).isEqualTo("success")
        assertThat(ClientOutcome.FAILURE.tagValue).isEqualTo("failure")
        assertThat(ClientOutcome.TIMEOUT.tagValue).isEqualTo("timeout")
        assertThat(ClientOutcome.CANCELLED.tagValue).isEqualTo("cancelled")
        assertThat(ClientOutcome.entries).hasSize(4)
    }

    @Test
    fun `should pin the fail-open stages and the request-id sources`() {
        // What is tested: the `stage` tag values of adapter.logging.failopen and the `source` tag
        //   values of adapter.logging.correlation.id.
        // Success criteria: emission, arrival, wiring and trace, header, generated - exactly.
        // Why it matters: the suggested alert set in the guide queries stage="emission" literally;
        //   the generated share of the source tag is the propagation-regression signal (ADR-0002).
        // Given/When/Then
        assertThat(FailOpenStage.entries.map { it.tagValue }).containsExactly("emission", "arrival", "wiring")
        assertThat(RequestIdSource.entries.map { it.tagValue }).containsExactly("trace", "header", "generated")
    }
}
