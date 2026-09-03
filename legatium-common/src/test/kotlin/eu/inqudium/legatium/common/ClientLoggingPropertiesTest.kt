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
            // Given/When/Then: neither zero nor a negative duration is a threshold
            assertThat(catchThrowable { ClientLoggingProperties(slowRequestThreshold = Duration.ZERO) })
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThat(catchThrowable { ClientLoggingProperties(slowRequestThreshold = Duration.ofMillis(-1)) })
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `should accept exactly one millisecond as the smallest threshold`() {
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
            // Given/When: the full RFC 9110 tchar set
            val properties = ClientLoggingProperties(correlationIdHeader = "X-Corr.Id_42!#$%&'*+^`|~")

            // Then
            assertThat(properties.correlationIdHeader).isEqualTo("X-Corr.Id_42!#$%&'*+^`|~")
        }
    }
}
