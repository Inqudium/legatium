package eu.inqudium.legatium.restclient.logging

import eu.inqudium.legatium.common.BodyReadState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.http.HttpHeaders
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
 * the capture with the stream - plus the guard on the tee stream's own close, and the read state the
 * tee records as the application opens and drains the body - plus the guards on the metadata accessors
 * and the tee stream's `available`, each driven to throw. The read guards, the status-code guard and the
 * response close guard are proved through the interceptor.
 */
class CapturingClientHttpResponseTest {
    private val failures = mutableListOf<Throwable>()

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

    /** The guard sites of the wrapper no other test drives, each as the delegate operation the caller performs. */
    internal enum class MetadataGuard(
        val message: String,
        val call: (CapturingClientHttpResponse) -> Any,
    ) {
        STATUS_TEXT("engine refused the status text", { it.statusText }),
        HEADERS("engine refused the headers", { it.headers }),
        AVAILABLE("engine refused available", { it.body.available() }),
    }

    /** An engine stream that reads fine and refuses to close - a connection that broke while it was being released. */
    private fun refusingToClose(text: String): InputStream =
        object : FilterInputStream(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8))) {
            override fun close(): Unit = throw IOException("connection broke on release")
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

        // When/Then: the peek-and-rewind of IntrospectingClientHttpResponse, then the full read
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
    fun `should rewind the capture to a mark taken mid-stream, not to the start`() {
        // What is tested: the tee's forwarding of mark() to the capture - a mark taken AFTER some bytes
        //   were read, which Spring's own peek (mark(1) before any read) never takes: there the
        //   capture's default mark, the start of the stream, coincides with the tee's.
        // Success criteria: with "hello" read 2, marked, read 2, reset and read to the end, the total
        //   is the body's 5 bytes and the logged text is "hello" - the prefix before the mark is
        //   neither lost nor counted twice; the state is COMPLETE, no failure was reported.
        // Why it matters: a custom extractor peeking mid-stream on a buffered response rewinds the
        //   stream to its mark; without the forwarding the capture rewinds to the START and the logged
        //   body loses its prefix while the size sample under-counts - silently, on every such call.
        // Given
        val capture = BoundedBodyCapture(16)
        val body = wrapping(ByteArrayInputStream("hello".toByteArray()), capture).body

        // When: two bytes consumed, a mark, two more, a rewind to the mark, the rest
        assertThat(body.readNBytes(2).toString(StandardCharsets.UTF_8)).isEqualTo("he")
        body.mark(8)
        assertThat(body.readNBytes(2).toString(StandardCharsets.UTF_8)).isEqualTo("ll")
        body.reset()
        val rest = body.readAllBytes().toString(StandardCharsets.UTF_8)

        // Then
        assertThat(rest).isEqualTo("llo")
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

        // When/Then
        assertThat(body.markSupported()).isFalse()
        body.mark(1)
        assertThat(body.read()).isEqualTo('h'.code)
        val thrown = catchThrowable { body.reset() }

        // Then
        assertThat(thrown).isInstanceOf(IOException::class.java).hasMessageContaining("not supported")
        assertThat(failures).containsExactly(thrown)
        assertThat(capture.totalBytes).isEqualTo(1L)
    }

    @Test
    fun `should record the read state of the response body as unread, partial or complete`() {
        // What is tested: the observation points of the read state on the tee - opening the stream
        //   marks PARTIAL, observing EOF marks COMPLETE, never opening it leaves UNREAD.
        // Success criteria: one wrapped response, the state read off its capture at three points:
        //   UNREAD before the body is opened, PARTIAL once it is and after a one-byte read, COMPLETE
        //   after the read to EOF.
        // Why it matters: the state is the one signal that tells a discarded response body from an
        //   absent one.
        // Given
        val capture = BoundedBodyCapture(8)
        val response = wrapping(ByteArrayInputStream("ab".toByteArray()), capture)

        // When/Then: the state at each observation point
        assertThat(capture.readState).isEqualTo(BodyReadState.UNREAD)
        val stream = response.body
        assertThat(capture.readState).isEqualTo(BodyReadState.PARTIAL)
        stream.read()
        assertThat(capture.readState).isEqualTo(BodyReadState.PARTIAL)
        stream.readAllBytes()
        assertThat(capture.readState).isEqualTo(BodyReadState.COMPLETE)
    }

    @Test
    fun `should report a body stream whose close throws and rethrow it unchanged`() {
        // What is tested: the guard on the tee stream's close - the engine stream's close throws after
        //   the body was read to its end; the exception is reported as the exchange's failure like a
        //   refused read and reaches the caller unchanged.
        // Success criteria: close() throws the engine's IOException; it is the one failure reported;
        //   the bytes read before it stay captured and the read state stays COMPLETE.
        // Why it matters: the converters close the body stream in a finally - unguarded, a close the
        //   caller sees fail would leave the exchange to log `success`; swallowed, it would hide from
        //   the caller a failure the exchange line reports.
        // Given
        val capture = BoundedBodyCapture(16)
        val body = wrapping(refusingToClose("hello"), capture).body

        // When: the full read, then the close the converter's finally performs
        val text = body.readAllBytes().toString(StandardCharsets.UTF_8)
        val thrown = catchThrowable { body.close() }

        // Then
        assertThat(text).isEqualTo("hello")
        assertThat(thrown).isInstanceOf(IOException::class.java).hasMessage("connection broke on release")
        assertThat(failures).containsExactly(thrown)
        assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("hello")
        assertThat(capture.readState).isEqualTo(BodyReadState.COMPLETE)
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

    @ParameterizedTest
    @EnumSource(MetadataGuard::class)
    internal fun `should report a delegate whose status text, headers or available throws and rethrow it unchanged`(site: MetadataGuard) {
        // What is tested: the guards at getStatusText, getHeaders and the tee stream's available - the
        //   three of the wrapper's five documented guard sites no other test drives (getStatusCode is
        //   pinned through the interceptor, the reads and closes above).
        // Success criteria: the delegate's IOException reaches the caller unchanged and is the single
        //   failure reported on the exchange.
        // Why it matters: RestClient's status handlers and converters read the status text and the
        //   headers before the body; an unguarded accessor would let the caller handle an exception
        //   while the exchange logs success - the contradiction the guards exist to prevent, and a
        //   regression every suite stayed green on.
        // Given: a delegate refusing exactly these operations
        val refusingBody =
            object : ByteArrayInputStream("x".toByteArray(StandardCharsets.UTF_8)) {
                override fun available(): Int = throw IOException("engine refused available")
            }
        val refusing =
            object : MockClientHttpResponse(refusingBody, HttpStatus.OK) {
                override fun getStatusText(): String = throw IOException("engine refused the status text")

                override fun getHeaders(): HttpHeaders = throw IOException("engine refused the headers")
            }
        val response = CapturingClientHttpResponse(refusing, BoundedBodyCapture(16), { failures += it }, {})

        // When
        val thrown = catchThrowable { site.call(response) }

        // Then
        assertThat(thrown).isInstanceOf(IOException::class.java).hasMessage(site.message)
        assertThat(failures).containsExactly(thrown)
    }
}
