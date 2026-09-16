package eu.inqudium.legatium.webclient.logging

import org.assertj.core.api.Assertions.assertThat
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

    @Test
    fun `should tee a single-buffer Mono body and hand the connector the identical content`() {
        // What is tested: CapturingClientHttpRequestDecorator.writeWith with a Mono body - the
        //   specialisation the connectors take for single-buffer requests.
        // Success criteria: the connector's request holds "hello", the capture counted 5 bytes and
        //   logs "hello".
        // Why it matters: a Mono body must stay a Mono (the connector's optimised path) and still be
        //   observed; a tee that demoted it to a Flux would change the connector's write path.
        // Given
        val capture = BoundedBodyCapture(64)
        val connector = connectorRequest()

        // When
        CapturingClientHttpRequestDecorator(connector, capture).writeWith(Mono.just(buffer("hello"))).block()

        // Then
        assertThat(connector.bodyAsString.block()).isEqualTo("hello")
        assertThat(capture.totalBytes).isEqualTo(5L)
        assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("hello")
    }

    @Test
    fun `should tee a multi-buffer Flux body across its chunks`() {
        // What is tested: CapturingClientHttpRequestDecorator.writeWith with a Flux body - several
        //   buffers, each teed on its way through.
        // Success criteria: the connector receives "hello world", the capture counted all 11 bytes
        //   and logs the concatenation.
        // Why it matters: a chunked request body (a streamed upload, a large JSON) arrives in pieces;
        //   the capture must see every piece in order.
        // Given
        val capture = BoundedBodyCapture(64)
        val connector = connectorRequest()

        // When
        CapturingClientHttpRequestDecorator(connector, capture).writeWith(Flux.just(buffer("hello "), buffer("world"))).block()

        // Then
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

    /** A mock connector request that offers zero-copy, recording which path a writer took. */
    private class ZeroCopyConnectorRequest :
        MockClientHttpRequest(HttpMethod.PUT, URI.create("https://api.example.com/upload")),
        ZeroCopyHttpOutputMessage {
        var zeroCopied: Triple<Path, Long, Long>? = null
        var bufferedWrites = 0

        override fun writeWith(
            file: Path,
            position: Long,
            count: Long,
        ): Mono<Void> = Mono.fromRunnable { zeroCopied = Triple(file, position, count) }

        override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
            bufferedWrites++
            return super<MockClientHttpRequest>.writeWith(body)
        }
    }
}
