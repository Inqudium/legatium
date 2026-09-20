package eu.inqudium.legatium.webclient.logging

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpMethod
import org.springframework.http.ZeroCopyHttpOutputMessage
import org.springframework.mock.http.client.reactive.MockClientHttpRequest
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeStrategies
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * The request-side tee decorators driven directly against a mock connector request: every write path a
 * `BodyInserter` can take - `writeWith(Mono)`, `writeWith(Flux)`, `writeAndFlushWith` (streaming media
 * types) and the zero-copy file transfer - counts and copies without changing what the connector
 * receives. The filter tests cover the `Mono` path through `BodyInserters.fromValue`; the other three
 * paths are only reachable here.
 */
class CapturingDecoratorsTest {
    private fun connectorRequest() = MockClientHttpRequest(HttpMethod.POST, URI.create("https://api.example.com/things"))

    /** A mock connector request that records the publisher SPECIALISATION handed to its buffered write path. */
    private class RecordingConnectorRequest : MockClientHttpRequest(HttpMethod.POST, URI.create("https://api.example.com/things")) {
        var written: Publisher<out DataBuffer>? = null

        override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
            written = body
            return super.writeWith(body)
        }
    }

    @Test
    fun `should tee a single-buffer Mono body and hand the connector the identical content`() {
        // What is tested: CapturingClientHttpRequestDecorator.writeWith with a Mono body - the
        //   specialisation the connectors take for single-buffer requests.
        // Success criteria: the connector's request holds "hello", the capture counted 5 bytes and
        //   logs "hello" - and the publisher the connector received is still a Mono.
        // Why it matters: a Mono body must stay a Mono (the connector's optimised single-buffer path)
        //   and still be observed; a tee that demoted it to a Flux would change the connector's write
        //   path while every byte still arrived - only the recorded publisher type shows it.
        // Given
        val capture = BoundedBodyCapture(64)
        val connector = RecordingConnectorRequest()

        // When
        CapturingClientHttpRequestDecorator(connector, capture).writeWith(Mono.just(buffer("hello"))).block()

        // Then
        assertThat(connector.written).isInstanceOf(Mono::class.java)
        assertThat(connector.bodyAsString.block()).isEqualTo("hello")
        assertThat(capture.totalBytes).isEqualTo(5L)
        assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("hello")
    }

    @Test
    fun `should hand the request's Content-Length to the capture at write time and fold a malformed one to unknown`() {
        // What is tested: the sizing hint the decorator passes on - the Content-Length on the
        //   connector request when writeWith runs (EncoderHttpMessageWriter sets it for a Mono body
        //   right before), and a caller-set value Spring cannot parse.
        // Success criteria: the capture's hint is 5 for a declared 5; UNKNOWN_LENGTH for "many",
        //   and the write still succeeds with the body captured; the flushing path hands the hint on
        //   the same way.
        // Why it matters: the hint sizes the capture's one block exactly; a peer- or caller-controlled
        //   header must never throw into the connector's write.
        // Given
        val declared = BoundedBodyCapture(64)
        val connector = connectorRequest().apply { headers.contentLength = 5 }
        val malformed = BoundedBodyCapture(64)
        val garbled = connectorRequest().apply { headers.set("Content-Length", "many") }
        val flushed = BoundedBodyCapture(64)
        val streaming = connectorRequest().apply { headers.contentLength = 9 }

        // When
        CapturingClientHttpRequestDecorator(connector, declared).writeWith(Mono.just(buffer("hello"))).block()
        CapturingClientHttpRequestDecorator(garbled, malformed).writeWith(Mono.just(buffer("hello"))).block()
        CapturingClientHttpRequestDecorator(streaming, flushed).writeAndFlushWith(Flux.just(Flux.just(buffer("a\n")))).block()

        // Then
        assertThat(declared.expectedBytes).isEqualTo(5L)
        assertThat(malformed.expectedBytes).isEqualTo(BoundedBodyCapture.UNKNOWN_LENGTH)
        assertThat(malformed.loggedValue(StandardCharsets.UTF_8)).isEqualTo("hello")
        assertThat(flushed.expectedBytes).isEqualTo(9L)
    }

    @Test
    fun `should tee a multi-buffer Flux body across its chunks`() {
        // What is tested: CapturingClientHttpRequestDecorator.writeWith with a Flux body - several
        //   buffers, each teed on its way through.
        // Success criteria: the connector receives "hello world", the capture counted all 11 bytes
        //   and logs the concatenation; the publisher the connector received is a Flux, not a Mono.
        // Why it matters: a chunked request body (a streamed upload, a large JSON) arrives in pieces;
        //   the capture must see every piece in order - and the Mono specialisation of the previous
        //   test must be a specialisation, not a wrap of every body into Mono.
        // Given
        val capture = BoundedBodyCapture(64)
        val connector = RecordingConnectorRequest()

        // When
        CapturingClientHttpRequestDecorator(connector, capture).writeWith(Flux.just(buffer("hello "), buffer("world"))).block()

        // Then
        assertThat(connector.written).isInstanceOf(Flux::class.java).isNotInstanceOf(Mono::class.java)
        assertThat(connector.bodyAsString.block()).isEqualTo("hello world")
        assertThat(capture.totalBytes).isEqualTo(11L)
        assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("hello world")
    }

    @Test
    fun `should tee a streaming writeAndFlushWith body through every inner publisher`() {
        // What is tested: CapturingClientHttpRequestDecorator.writeAndFlushWith - the path of
        //   streaming media types (text/event-stream, application/x-ndjson), where the inserter hands
        //   the connector a publisher of publishers and each inner one is flushed as a unit.
        // Success criteria: the connector receives the three events in order, the capture counted
        //   every byte and logs the concatenation.
        // Why it matters: this path is taken by no other test; an unwrapped inner publisher would
        //   silently skip the tee for exactly the request bodies that stream.
        // Given
        val capture = BoundedBodyCapture(64)
        val connector = connectorRequest()
        val events =
            Flux.just(
                Flux.just(buffer("a\n")),
                Flux.just(buffer("bb"), buffer("\n")),
                Flux.just(buffer("ccc\n")),
            )

        // When
        CapturingClientHttpRequestDecorator(connector, capture).writeAndFlushWith(events).block()

        // Then
        assertThat(connector.bodyAsString.block()).isEqualTo("a\nbb\nccc\n")
        assertThat(capture.totalBytes).isEqualTo(9L)
        assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("a\nbb\nccc\n")
    }

    @Test
    fun `should count a zero-copy file transfer without copying and without demoting it to buffered writes`() {
        // What is tested: ZeroCopyCapturingClientHttpRequestDecorator through the real inserter chain -
        //   a file Resource body on a connector request that offers zero-copy, so
        //   ResourceHttpMessageWriter picks the zero-copy path through the DECORATOR.
        // Success criteria: the connector's zero-copy method received the file with position 0 and
        //   the full length, nothing went through its buffered write path, and the capture counted
        //   the length without holding a byte - the logged value is the truncation note alone, with
        //   an empty prefix, exactly as the guide describes a zero-copy upload under body logging.
        // Why it matters: a plain ClientHttpRequestDecorator would hide the zero-copy contract and
        //   silently turn every file upload into a buffered write - the regression the class exists to
        //   prevent, and one no build signal showed before this test.
        // Given: a temporary file and a connector request that offers zero-copy
        val file = Files.createTempFile("legatium-zero-copy", ".bin")
        try {
            Files.write(file, ByteArray(1_234) { it.toByte() })
            val capture = BoundedBodyCapture(64)
            val connector = ZeroCopyConnectorRequest()
            val request =
                ClientRequest
                    .create(HttpMethod.PUT, URI.create("https://api.example.com/upload"))
                    .body(BodyInserters.fromResource(FileSystemResource(file)))
                    .build()

            // When: the filter's rebuild, then the connector's write of it
            request.withRequestBodyTee(capture).writeTo(connector, ExchangeStrategies.withDefaults()).block()

            // Then
            assertThat(connector.zeroCopied).isEqualTo(Triple(file, 0L, 1_234L))
            assertThat(connector.bufferedWrites).isZero()
            assertThat(capture.totalBytes).isEqualTo(1_234L)
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("... [truncated, 1234 bytes total]")
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `should wrap a connector request without zero-copy support in the plain decorator`() {
        // What is tested: withRequestBodyTee's choice of decorator - a connector request that does NOT
        //   implement ZeroCopyHttpOutputMessage gets the plain tee, and a file body then goes through
        //   the buffered write path, teed like any other body.
        // Success criteria: the mock connector holds the file's bytes, the capture counted them.
        // Why it matters: the zero-copy decorator casts its delegate; choosing it for a connector
        //   without the contract would fail the first file upload with a ClassCastException.
        // Given
        val file = Files.createTempFile("legatium-buffered", ".txt")
        try {
            Files.writeString(file, "file body")
            val capture = BoundedBodyCapture(64)
            val connector = connectorRequest()
            val request =
                ClientRequest
                    .create(HttpMethod.PUT, URI.create("https://api.example.com/upload"))
                    .body(BodyInserters.fromResource(FileSystemResource(file)))
                    .build()

            // When
            request.withRequestBodyTee(capture).writeTo(connector, ExchangeStrategies.withDefaults()).block()

            // Then
            assertThat(connector.bodyAsString.block()).isEqualTo("file body")
            assertThat(capture.totalBytes).isEqualTo(9L)
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("file body")
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `should count nothing for a zero-copy transfer that fails`() {
        // What is tested: the placement of the zero-copy count on the connector's SUCCESS signal - the
        //   decision that a failed transfer counts nothing, pinned.
        // Success criteria: the failure reaches the writer; the capture counted zero bytes.
        // Why it matters: the connector reports no partial count for a failed sendfile, and the size
        //   meter describes bytes that flowed - counting the attempted length would inflate it by
        //   exactly the uploads that failed, and the operator would read a full upload where none
        //   happened.
        // Given: a connector whose transfer fails
        val capture = BoundedBodyCapture(64)
        val connector = ZeroCopyConnectorRequest().apply { transferFailure = IOException("connection reset during sendfile") }

        // When
        val thrown = catchThrowable { ZeroCopyCapturingClientHttpRequestDecorator(connector, capture).writeWith(Path.of("upload.bin"), 0, 1_234).block() }

        // Then
        assertThat(thrown).hasMessageContaining("connection reset during sendfile")
        assertThat(capture.totalBytes).isZero()
        assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isNull()
    }

    /** A mock connector request that offers zero-copy, recording which path a writer took; its transfer fails with [transferFailure] when set. */
    private class ZeroCopyConnectorRequest :
        MockClientHttpRequest(HttpMethod.PUT, URI.create("https://api.example.com/upload")),
        ZeroCopyHttpOutputMessage {
        var zeroCopied: Triple<Path, Long, Long>? = null
        var bufferedWrites = 0
        var transferFailure: Throwable? = null

        override fun writeWith(
            file: Path,
            position: Long,
            count: Long,
        ): Mono<Void> = transferFailure?.let { Mono.error(it) } ?: Mono.fromRunnable { zeroCopied = Triple(file, position, count) }

        override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
            bufferedWrites++
            return super<MockClientHttpRequest>.writeWith(body)
        }
    }
}
