package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The acceptance rule for a caller-supplied correlation id (ADR-0002 amendment). */
class CorrelationHeaderTest {
    @Test
    fun `should accept a visible-ASCII id within the length bound`() {
        // What is tested: the positive side of the rule - the id shapes real systems send.
        // Success criteria: UUIDs, base-36 ids and ids with the usual punctuation are accepted verbatim.
        // Why it matters: an over-strict rule would replace legitimate ids and break the join with the
        //   caller's logs.
        // Given/When/Then
        assertThat(CorrelationHeader.accept("4bf92f3577b34da6a3ce929d0e0e4736")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736")
        assertThat(CorrelationHeader.accept("req-01J8Z-abc_DEF.42:7/x+y~z")).isEqualTo("req-01J8Z-abc_DEF.42:7/x+y~z")
        assertThat(CorrelationHeader.accept("a".repeat(CorrelationHeader.MAX_LENGTH))).hasSize(CorrelationHeader.MAX_LENGTH)
    }

    @Test
    fun `should treat control characters, whitespace, non-ASCII, emptiness and oversize as absent`() {
        // What is tested: the rejection side - every class of value that could forge a log line, bloat
        //   the MDC or is not an id at all.
        // Success criteria: null for each, so the twin generates and sends its own id instead.
        // Why it matters: the value lands verbatim in the message, the MDC and the header field of every
        //   line of the call; a CR/LF inside it forges lines in every plain-text sink.
        // Given/When/Then
        assertThat(CorrelationHeader.accept(null)).isNull()
        assertThat(CorrelationHeader.accept("")).isNull()
        assertThat(CorrelationHeader.accept("   ")).isNull()
        assertThat(CorrelationHeader.accept("abc\r\nforged=line")).isNull()
        assertThat(CorrelationHeader.accept("abc def")).isNull()
        assertThat(CorrelationHeader.accept("abc\u0000")).isNull()
        assertThat(CorrelationHeader.accept("id-\u00e4")).isNull()
        assertThat(CorrelationHeader.accept("a".repeat(CorrelationHeader.MAX_LENGTH + 1))).isNull()
    }

    @Test
    fun `should bound the id at 200 characters, as a literal`() {
        // What is tested: the length bound as a LITERAL, independent of the constant - the value a peer
        //   that propagates ids has to stay under.
        // Success criteria: 200 characters accepted, 201 treated as absent.
        // Why it matters: the bound is a cross-repository contract (ADR-0002) that no build can check
        //   against the peer's constant; the sibling project limesium currently bounds inbound ids at
        //   128, so an id of 129 to 200 characters is sent by this twin and replaced by that peer. A
        //   change to either side must be a visible decision, not a silent drift.
        // Given/When/Then
        assertThat(CorrelationHeader.MAX_LENGTH).isEqualTo(200)
        assertThat(CorrelationHeader.accept("a".repeat(200))).hasSize(200)
        assertThat(CorrelationHeader.accept("a".repeat(201))).isNull()
    }
}
