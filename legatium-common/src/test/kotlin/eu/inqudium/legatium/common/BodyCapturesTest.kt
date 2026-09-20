package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * The capture rule shared by both twins (ADR-0003): which direction gets a buffering, a count-only or
 * no capture, from the body modes and the measuring switches. The twins' body-and-header suites pin
 * what the captures then do; this one pins the choice.
 */
class BodyCapturesTest {
    /** Stands in for a twin's `BoundedBodyCapture`: the rule sees only the limit it hands over. */
    private data class FakeCapture(
        val maxBytes: Int,
    )

    @ParameterizedTest
    @EnumSource(BodyLogMode::class, names = ["ON_FAILURE", "ALWAYS"])
    fun `should buffer up to the limit when the body is logged, in any mode`(mode: BodyLogMode) {
        // What is tested: a direction whose mode captures gets a capture with the configured limit -
        //   on-failure as much as always, because the request body flows before the outcome is known.
        // Success criteria: both directions carry a capture of maxBodyBytes; the measuring switches
        //   are irrelevant once the mode captures.
        // Why it matters: a smaller limit for on-failure would truncate exactly the bodies the mode
        //   exists to show.
        // Given
        val properties = ClientLoggingProperties(logRequestBody = mode, logResponseBody = mode, maxBodyBytes = 512)

        // When
        val captures = BodyCaptures.of(properties, ::FakeCapture)

        // Then
        assertThat(captures.request).isEqualTo(FakeCapture(512))
        assertThat(captures.response).isEqualTo(FakeCapture(512))
    }

    @Test
    fun `should install a count-only capture when the body is merely measured`() {
        // What is tested: body logging off, size measuring on, per direction.
        // Success criteria: a capture with limit 0 in both directions - the bytes are counted, none is
        //   buffered.
        // Why it matters: the size meters must not depend on a logging flag (the measuring properties'
        //   contract), and a limit above zero would buffer bodies nobody logs.
        // Given
        val properties = ClientLoggingProperties(measureRequestBodySize = true, measureResponseBodySize = true)

        // When
        val captures = BodyCaptures.of(properties, ::FakeCapture)

        // Then
        assertThat(captures.request).isEqualTo(FakeCapture(0))
        assertThat(captures.response).isEqualTo(FakeCapture(0))
    }

    @Test
    fun `should install nothing when the body is neither logged nor measured`() {
        // What is tested: the shipped defaults - never, not measured.
        // Success criteria: no capture in either direction, so the tee is not even wired.
        // Why it matters: the default configuration must not pay for a body nobody wants.
        // Given/When
        val captures = BodyCaptures.of(ClientLoggingProperties(), ::FakeCapture)

        // Then
        assertThat(captures.request).isNull()
        assertThat(captures.response).isNull()
    }

    @Test
    fun `should decide each direction on its own`() {
        // What is tested: the request logged, the response only measured.
        // Success criteria: a buffering capture for the request, a count-only one for the response.
        // Why it matters: the two directions are configured independently and must not leak into each
        //   other's choice.
        // Given
        val properties = ClientLoggingProperties(logRequestBody = BodyLogMode.ALWAYS, measureResponseBodySize = true, maxBodyBytes = 64)

        // When
        val captures = BodyCaptures.of(properties, ::FakeCapture)

        // Then
        assertThat(captures.request).isEqualTo(FakeCapture(64))
        assertThat(captures.response).isEqualTo(FakeCapture(0))
    }
}
