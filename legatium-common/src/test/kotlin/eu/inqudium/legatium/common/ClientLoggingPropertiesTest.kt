package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration

/** Construction-time invariants of [ClientLoggingProperties]. */
class ClientLoggingPropertiesTest {
    @Nested
    inner class `Slow request threshold` {
        @Test
        fun `should reject a positive threshold below one millisecond`() {
            // What is tested: the resolution floor -
            //   the logged duration has millisecond resolution, so a sub-millisecond threshold would
            //   flag calls whose logged duration reads 0 ms.
            // Success criteria: construction fails with a message naming the floor.
            // Why it matters: a silently accepted 500us threshold escalates all traffic to WARN.
            // Given/When
            val thrown = catchThrowable { ClientLoggingProperties(slowRequestThreshold = Duration.ofNanos(999_999)) }

            // Then
            assertThat(thrown)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("at least 1 millisecond")
        }

        @Test
        fun `should reject zero and negative thresholds`() {
            // What is tested: the lower end of the threshold check below the resolution floor - zero and a
            //   negative duration.
            // Success criteria: both fail construction with an IllegalArgumentException.
            // Why it matters: a zero threshold flags every call as slow and escalates the whole stream to
            //   WARN; a negative one is a binding typo that would do the same.
            // Given/When/Then: neither zero nor a negative duration is a threshold
            assertThat(catchThrowable { ClientLoggingProperties(slowRequestThreshold = Duration.ZERO) })
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThat(catchThrowable { ClientLoggingProperties(slowRequestThreshold = Duration.ofMillis(-1)) })
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `should accept exactly one millisecond as the smallest threshold`() {
            // What is tested: the boundary of the `toMillis() >= 1` check - the smallest legal threshold.
            // Success criteria: construction succeeds and the property holds exactly one millisecond.
            // Why it matters: an off-by-one in the floor would reject the documented minimum and fail the
            //   context start of a host that tuned the threshold down.
            // Given/When
            val properties = ClientLoggingProperties(slowRequestThreshold = Duration.ofMillis(1))

            // Then
            assertThat(properties.slowRequestThreshold).isEqualTo(Duration.ofMillis(1))
        }
    }

    @Nested
    inner class `Masking key` {
        @Test
        fun `should reject a blank masking key but accept an empty one`() {
            // What is tested: the binding-time rule - empty means unkeyed, blank is a misconfiguration.
            // Success criteria: whitespace fails construction naming the property; the empty default binds.
            // Why it matters: a whitespace key would silently key the fingerprint with a worthless secret.
            // Given/When/Then
            assertThat(catchThrowable { ClientLoggingProperties(maskingKey = "  ") })
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("maskingKey")
            assertThat(ClientLoggingProperties().maskingKey).isEmpty()
        }

        @Test
        fun `should redact the masking key in toString`() {
            // What is tested: the key is a secret - a properties dump (a startup log, a debug endpoint)
            //   must not print it.
            // Success criteria: toString carries the redaction marker, never the key; the empty default
            //   renders empty.
            // Why it matters: data-class toString would otherwise leak the secret into every context
            //   that prints the bean.
            // Given/When/Then
            assertThat(ClientLoggingProperties(maskingKey = "pepper").toString()).contains("maskingKey=<redacted>").doesNotContain("pepper")
            assertThat(ClientLoggingProperties().toString()).contains("maskingKey=)")
        }
    }

    @Nested
    inner class `Excluded hosts` {
        @Test
        fun `should reject blank host entries`() {
            // What is tested: the blank-entry check of `excludeHosts` next to a valid entry.
            // Success criteria: construction fails with a message naming the property.
            // Why it matters: the host match is case-insensitive equality, so a blank entry can never match -
            //   the operator believes a peer is excluded while its calls keep logging.
            // Given/When/Then: a blank host can never match and hides a configuration mistake
            assertThat(catchThrowable { ClientLoggingProperties(excludeHosts = listOf("pushgateway", " ")) })
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("excludeHosts")
        }
    }

    @Nested
    inner class `Correlation id header` {
        @Test
        fun `should reject a correlation header name outside the HTTP field-name grammar`() {
            // What is tested: binding-time validation of the header NAME - the name is written onto every
            //   traceless outgoing request, and an HTTP engine that validates field names rejects a
            //   non-token at runtime on every call.
            // Success criteria: whitespace, separators and a non-ASCII character fail construction
            //   with a message naming the property.
            // Why it matters: a runtime rejection would fail the CALL itself, not merely the log line -
            //   a logging library turning into an outage.
            // Given/When/Then: each invalid name is rejected at construction
            listOf("X Correlation", "X:Correlation", "X-Correlation\u00e9", "X(Corr)", "X,Corr").forEach { name ->
                assertThat(catchThrowable { ClientLoggingProperties(correlationIdHeader = name) })
                    .describedAs("name %s", name)
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("correlationIdHeader must be a valid HTTP field name")
            }
        }

        @Test
        fun `should accept every token character of a field name`() {
            // What is tested: the positive side of the RFC 9110 field-name check - every tchar in one name.
            // Success criteria: construction succeeds and the name binds unchanged.
            // Why it matters: the regex is hand-written; a missing special character would reject a legal
            //   header name and fail the context start for a host with an unusual but valid convention.
            // Given/When: the full RFC 9110 tchar set
            val properties = ClientLoggingProperties(correlationIdHeader = "X-Corr.Id_42!#$%&'*+^`|~")

            // Then
            assertThat(properties.correlationIdHeader).isEqualTo("X-Corr.Id_42!#$%&'*+^`|~")
        }
    }

    @Nested
    inner class `Binding-time validation` {
        @Test
        fun `should reject a blank logger name and a blank correlation header name`() {
            // What is tested: the two blank-name checks - a logger with no name and a header with no name
            //   are both misconfigurations Boot would bind silently.
            // Success criteria: construction fails naming the property.
            // Why it matters: a blank logger name routes the exchange stream nowhere an operator expects; a
            //   blank header name cannot go on the wire.
            // Given/When/Then
            assertThat(catchThrowable { ClientLoggingProperties(loggerName = " ") })
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("loggerName")
            assertThat(catchThrowable { ClientLoggingProperties(correlationIdHeader = "") })
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("correlationIdHeader")
        }

        @Test
        fun `should reject a non-positive body capture limit`() {
            // What is tested: `max-body-bytes` must be positive - count-only mode is selected by the
            //   body modes, never by a zero limit.
            // Success criteria: zero and a negative limit fail naming the property.
            // Why it matters: a zero limit would log every body as truncated to nothing without saying why.
            // Given/When/Then
            assertThat(catchThrowable { ClientLoggingProperties(maxBodyBytes = 0) })
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("maxBodyBytes must be positive")
            assertThat(catchThrowable { ClientLoggingProperties(maxBodyBytes = -1) })
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `should reject blank entries in the activation lists`() {
            // What is tested: the three blank-entry checks of the activation lists.
            // Success criteria: each list rejects a blank entry with a message naming the list.
            // Why it matters: a blank pattern or prefix matches nothing or everything by accident.
            // Given/When/Then
            assertThat(catchThrowable { ClientLoggingProperties(includePathPatterns = listOf("/api/**", " ")) })
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("includePathPatterns")
            assertThat(catchThrowable { ClientLoggingProperties(excludePathPrefixes = listOf("")) })
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("excludePathPrefixes")
            assertThat(catchThrowable { ClientLoggingProperties(excludeHosts = listOf("\t")) })
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("excludeHosts")
        }
    }
}
