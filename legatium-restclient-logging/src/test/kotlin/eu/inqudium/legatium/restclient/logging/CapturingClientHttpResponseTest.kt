package eu.inqudium.legatium.restclient.logging

import eu.inqudium.legatium.common.BodyReadState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.client.MockClientHttpResponse
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * The tee stream's transparency towards the application: the engine stream's `mark`/`reset` contract is
 * forwarded as it is - present on a buffered response, absent on an engine stream - and a rewind moves
 * the capture with the stream. The read and close guards are proved through the interceptor.
 */
class CapturingClientHttpResponseTest {
    private val failures = mutableListOf<Exception>()

    private fun wrapping(
        body: InputStream,
        capture: BoundedBodyCapture?,
    ): CapturingClientHttpResponse =
        CapturingClientHttpResponse(
            delegate = MockClientHttpResponse(body, HttpStatus.OK),
            capture = capture,
            onFailure = { failures += it },
            onClose = {},
        )

    /** An engine-like stream: no mark support, whatever the stream underneath offers. */
    private fun engineStream(text: String): InputStream =
        object : FilterInputStream(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8))) {
            override fun markSupported(): Boolean = false

            override fun reset(): Unit = throw IOException("mark/reset not supported")
        }

    @Test
    fun `should keep a buffered response rewindable and count the replayed bytes once`() {
        // What is tested: the tee over a ByteArrayInputStream body (Spring's buffering request
        //   factory, the mock response) - markSupported stays true, and Spring's own peek (mark(1),
        //   read(), reset()) followed by the converter's full read is captured exactly once.
        // Success criteria: markSupported is true through the wrapper; after the peek and the full read
        //   the total is the body's 5 bytes, the logged text is the body without a duplicated first
        //   byte, and the state is COMPLETE; no failure was reported.
        // Why it matters: a bare InputStream subclass answered false and threw on reset, so the
        //   library's presence changed what the application saw on its stream - and forwarding the
        //   reset without rewinding the capture would have counted and logged the peeked byte twice.
        // Given
        val capture = BoundedBodyCapture(16)
        val body = wrapping(ByteArrayInputStream("hello".toByteArray()), capture).body

        // When: the peek-and-rewind of IntrospectingClientHttpResponse, then the full read
        assertThat(body.markSupported()).isTrue()
        body.mark(1)
        assertThat(body.read()).isEqualTo('h'.code)
        body.reset()
        val text = body.readAllBytes().toString(StandardCharsets.UTF_8)

        // Then
        assertThat(text).isEqualTo("hello")
        assertThat(capture.totalBytes).isEqualTo(5L)
        assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("hello")
        assertThat(capture.readState).isEqualTo(BodyReadState.COMPLETE)
        assertThat(failures).isEmpty()
    }

    @Test
    fun `should report an engine stream as not rewindable and count a refused reset as a failure`() {
        // What is tested: the tee over a stream without mark support - the answer is forwarded, a
        //   mark is the no-op the contract allows, and the IOException of a reset the engine refuses
        //   reaches the caller unchanged AND is reported like every other refused engine call.
        // Success criteria: markSupported is false; mark does not throw; reset throws the engine's
        //   IOException, which is the one failure reported; the capture is not rewound.
        // Why it matters: the wrapper must neither invent a capability the engine lacks nor hide the
        //   refusal from the exchange's outcome - a caller that fails on the stream is never logged as
        //   a success.
        // Given
        val capture = BoundedBodyCapture(16)
        val body = wrapping(engineStream("hello"), capture).body

        // When
        assertThat(body.markSupported()).isFalse()
        body.mark(1)
        assertThat(body.read()).isEqualTo('h'.code)
        val thrown = catchThrowable { body.reset() }

        // Then
        assertThat(thrown).isInstanceOf(IOException::class.java).hasMessageContaining("not supported")
        assertThat(failures).containsExactly(thrown as Exception)
        assertThat(capture.totalBytes).isEqualTo(1L)
    }

    @Test
    fun `should forward mark and reset without a capture attached`() {
        // What is tested: the measure-off, log-off wiring, where the wrapper exists only for the
        //   failure guards - the rewind must still reach the engine stream.
        // Success criteria: after mark, a read and reset, the full read yields the whole body.
        // Why it matters: the wrapper is installed on every logged exchange, so its transparency
        //   must not depend on a capture being present.
        // Given
        val body = wrapping(ByteArrayInputStream("hello".toByteArray()), capture = null).body

        // When
        body.mark(2)
        body.read()
        body.reset()

        // Then
        assertThat(body.readAllBytes().toString(StandardCharsets.UTF_8)).isEqualTo("hello")
        assertThat(failures).isEmpty()
    }
}
