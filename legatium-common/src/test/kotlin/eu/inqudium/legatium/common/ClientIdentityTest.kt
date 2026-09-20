package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders

/**
 * The precedence of [ClientIdentity.resolve] (ADR-0002), pinned where it is defined instead of through
 * one twin's entry point: a conformant `traceparent` outranks the correlation header, the header
 * outranks generation, only a generated id goes on the wire, and `generatedEarlier` keeps a re-entry
 * counting as generated.
 */
class ClientIdentityTest {
    private val properties = ClientLoggingProperties()
    private val generator = CorrelationIdGenerator { "generated-42" }

    private fun headers(vararg entries: Pair<String, String>): HttpHeaders = HttpHeaders().apply { entries.forEach { (name, value) -> set(name, value) } }

    /** What one resolution must yield: request id, source, trace id, span id, and whether the header is sent. */
    private data class Expected(
        val requestId: String,
        val source: RequestIdSource,
        val traceId: String?,
        val spanId: String?,
        val sendCorrelationHeader: Boolean,
    )

    private fun resolved(
        headers: HttpHeaders,
        generatedEarlier: String? = null,
    ): Expected =
        ClientIdentity.resolve(headers, properties, generator, generatedEarlier).let {
            Expected(it.requestId, it.source, it.traceId, it.spanId, it.sendCorrelationHeader)
        }

    @Test
    fun `should resolve trace over header over generation and send the header only for a generated id`() {
        // What is tested: every branch of resolve as a table - the trace id wins and silences the
        //   header, an acceptable header wins over generation, anything else generates and sends, a
        //   header outside the acceptance rule counts as absent, a malformed traceparent counts as
        //   absent, and a re-entry that finds the id generated earlier keeps counting as generated
        //   without sending it again.
        // Success criteria: each row yields exactly the expected id, source, trace context and wire
        //   decision.
        // Why it matters: both twins inline this class (ADR-0003); until here its precedence was proven
        //   through the blocking twin alone, and the reactive twin has no re-entry path at all.
        // Given/When
        val trace = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
        val cases =
            mapOf(
                "trace and header" to
                    (
                        resolved(headers(Traceparent.HEADER to trace, "X-Correlation-Id" to "caller-1")) to
                            Expected("0af7651916cd43dd8448eb211c80319c", RequestIdSource.TRACE, "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", false)
                    ),
                "header only" to (resolved(headers("X-Correlation-Id" to "caller-1")) to Expected("caller-1", RequestIdSource.HEADER, null, null, false)),
                "nothing" to (resolved(headers()) to Expected("generated-42", RequestIdSource.GENERATED, null, null, true)),
                "header outside the rule" to (resolved(headers("X-Correlation-Id" to "bad id")) to Expected("generated-42", RequestIdSource.GENERATED, null, null, true)),
                "malformed traceparent with header" to
                    (resolved(headers(Traceparent.HEADER to "00-abc-def", "X-Correlation-Id" to "caller-1")) to Expected("caller-1", RequestIdSource.HEADER, null, null, false)),
                "re-entry with the id generated earlier" to
                    (resolved(headers("X-Correlation-Id" to "generated-42"), generatedEarlier = "generated-42") to Expected("generated-42", RequestIdSource.GENERATED, null, null, false)),
                "re-entry, but the header is the caller's" to
                    (resolved(headers("X-Correlation-Id" to "caller-1"), generatedEarlier = "generated-42") to Expected("caller-1", RequestIdSource.HEADER, null, null, false)),
            )

        // Then
        cases.forEach { (name, actualAndExpected) ->
            val (actual, expected) = actualAndExpected
            assertThat(actual).describedAs(name).isEqualTo(expected)
        }
    }
}
