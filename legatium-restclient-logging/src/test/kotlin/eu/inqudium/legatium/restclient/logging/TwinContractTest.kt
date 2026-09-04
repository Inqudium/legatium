package eu.inqudium.legatium.restclient.logging

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
        // What is tested: the duplicated meter-name constants, spelled out as literals - the
        //   cross-module lockstep tests cover configuration and field names, but not these.
        // Success criteria: every meter name matches the literal both twins ship.
        // Why it matters: a renamed meter in ONE twin would split every dashboard by stack - silently.
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
        // What is tested: the tagValue literals of BodyReadState and the size of the enum - the
        //   state tag of adapter.response.body.read, spelled out rather than derived from the enum.
        // Success criteria: unread, partial and complete exactly, and no fourth value.
        // Why it matters: the tag values are what alert rules and dashboards key on across both
        //   twins; an added or renamed state would split or silently empty those queries.
        // Given/When/Then: the literal tag values, pinned
        assertThat(BodyReadState.UNREAD.tagValue).isEqualTo("unread")
        assertThat(BodyReadState.PARTIAL.tagValue).isEqualTo("partial")
        assertThat(BodyReadState.COMPLETE.tagValue).isEqualTo("complete")
        assertThat(BodyReadState.entries).hasSize(3)
    }

    @Test
    fun `should pin the MDC keys to the literal twin contract`() {
        // What is tested: the MdcKeys and TraceMdcKeys literals - the adapter_ family every line of
        //   a call carries, and Boot's traceId/spanId keys the emission overlay reuses.
        // Success criteria: adapter_request_id, adapter_method, adapter_route, traceId and spanId,
        //   spelled exactly so.
        // Why it matters: an encoder emits these as fields by name - the adapter_ prefix keeps them
        //   beside limesium's endpoint_ keys, and only Boot's own trace key names make the join with
        //   the tracing bridge hold.
        // Given/When/Then: the literal MDC keys, pinned - the client family beside limesium's
        //   endpoint family, and Boot's own trace keys for the join
        assertThat(MdcKeys.REQUEST_ID).isEqualTo("adapter_request_id")
        assertThat(MdcKeys.REQUEST_METHOD).isEqualTo("adapter_method")
        assertThat(MdcKeys.ROUTE).isEqualTo("adapter_route")
        assertThat(TraceMdcKeys.TRACE_ID).isEqualTo("traceId")
        assertThat(TraceMdcKeys.SPAN_ID).isEqualTo("spanId")
    }

    @Test
    fun `should pin the masking fingerprint format to the literal twin contract`() {
        // What is tested: HeaderValueMasker.DEFAULT end to end - character length, colon, first 8
        //   bytes of SHA-256 over the UTF-8 bytes as lowercase hex - against a hardcoded expected
        //   value.
        // Success criteria: "secret-token" renders as "12:930bbdc51b6aed5c", the same literal the
        //   other twin and limesium pin.
        // Why it matters: the fingerprint is stable so a masked token correlates across the server
        //   line and both client lines; a changed digest prefix or hex casing would break that
        //   correlation without failing anything.
        // The expected value is hardcoded, not derived: the first 64 bits of SHA-256 over the UTF-8
        //   bytes are stable across JVMs - and identical to limesium's, so a masked token correlates
        //   across the server line and the client line.
        // Given/When/Then
        assertThat(HeaderValueMasker.DEFAULT.mask("secret-token")).isEqualTo("12:930bbdc51b6aed5c")
    }

    @Test
    fun `should pin the outcome vocabulary of this stack`() {
        // What is tested: the tagValue literals of the three ClientOutcome values the RestClient
        //   stack pre-registers on the events counter - success, failure and timeout; cancelled
        //   belongs to the reactive twin alone.
        // Success criteria: the three tag values match the literals exactly.
        // Why it matters: adapter_outcome and the outcome tag of adapter.logging.events are queried
        //   by these strings; a renamed value would empty every existing alert on this stack.
        // Given/When/Then: the literal outcome vocabulary, pinned - the blocking stack has no
        //   cancellation
        assertThat(ClientOutcome.SUCCESS.tagValue).isEqualTo("success")
        assertThat(ClientOutcome.FAILURE.tagValue).isEqualTo("failure")
        assertThat(ClientOutcome.TIMEOUT.tagValue).isEqualTo("timeout")
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
