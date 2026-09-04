package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * W3C conformance of [Traceparent], driven by the fixture `traceparent/conformance.txt` - the
 * conformance rules are thereby a build contract, not a KDoc rule (the rules themselves originate
 * from internal code analyses).
 */
class TraceparentTest {
    @Test
    fun `should accept every conformant header of the shared fixture with the expected identifiers`() {
        // What is tested: the accepting side of the parser - each valid fixture line yields the trace id
        //   and the parent id it names, including higher versions with extra fields.
        // Success criteria: the fixture is non-empty and every line parses to its expected pair.
        // Why it matters: a rejected valid header drops the call out of the trace and into the
        //   correlation-header path, so its log line no longer joins the tracing infrastructure.
        // Given: the valid lines of the shared fixture
        val cases = TraceparentConformanceFixture.valid()
        assertThat(cases).isNotEmpty()

        // When/Then: each parses to the expected pair
        cases.forEach { (header, traceId, spanId) ->
            assertThat(Traceparent.parse(header)).describedAs(header).isEqualTo(traceId to spanId)
        }
    }

    @Test
    fun `should reject every non-conformant header of the shared fixture`() {
        // What is tested: identifier shape, all-zero ids, version (two lowercase hex, not ff, exactly four
        //   fields for 00), flags (two lowercase hex) and structure - the cases the fixture enumerates.
        // Success criteria: null for each.
        // Why it matters: an accepted invalid header lands under the traceId/spanId MDC keys and
        //   produces joins the tracing infrastructure does not contain.
        // Given: the invalid lines of the shared fixture
        val cases = TraceparentConformanceFixture.invalid()
        assertThat(cases).isNotEmpty()

        // When/Then
        cases.forEach { header -> assertThat(Traceparent.parse(header)).describedAs(header).isNull() }
    }

    @Test
    fun `should treat an absent header as no trace context`() {
        // What is tested: the null branch at the top of `parse`.
        // Success criteria: null in, null out - no exception.
        // Why it matters: most outbound calls of a host without tracing carry no header; the traceless
        //   path starts here and must not pay an exception per call.
        // Given/When/Then
        assertThat(Traceparent.parse(null)).isNull()
    }
}
