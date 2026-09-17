package eu.inqudium.legatium.webclient.logging

import eu.inqudium.legatium.common.ClientLoggingProperties
import org.reactivestreams.Publisher
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.ZeroCopyHttpOutputMessage
import org.springframework.http.client.reactive.ClientHttpRequest
import org.springframework.http.client.reactive.ClientHttpRequestDecorator
import org.springframework.web.reactive.function.client.ClientRequest
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.nio.ByteBuffer
import java.nio.file.Path

/**
 * The reactive tee: every [DataBuffer] that flows is COUNTED in full FIRST, then at most the capture's
 * remaining capacity is copied out of it (a non-advancing read - the read position stays untouched),
 * and the ORIGINAL buffer continues downstream unchanged - ownership, pooling and release semantics are
 * exactly those of an undecorated exchange (the reactive counterpart of the RestClient module's tee
 * stream: a passive copy, never a pre-read or replay). Counting cannot throw; the copy can (an exotic
 * `DataBuffer`), and [ObservedBody] lets it - so the order makes a copy that throws cost the logged text
 * of that chunk, never the size sample. Transient allocation is bounded by
 * [ClientLoggingProperties.maxBodyBytes], not by the buffer size, and count-only captures (limit 0)
 * copy nothing at all.
 */
internal fun tee(
    capture: BoundedBodyCapture,
    buffer: DataBuffer,
): DataBuffer {
    val length = buffer.readableByteCount()
    capture.count(length)
    val wanted = minOf(length, capture.remainingCapacity())
    if (wanted > 0) {
        val prefix = ByteArray(wanted)
        buffer.toByteBuffer(buffer.readPosition(), ByteBuffer.wrap(prefix), 0, wanted)
        capture.store(prefix, 0, wanted)
    }
    return buffer
}

/**
 * Tees the REQUEST body into [capture] as the client writes it to the connector: the decorator wraps the
 * connector's request while the caller's `BodyInserter` runs, so the bytes are counted and copied at the
 * one place every body encoder passes through - `writeWith`/`writeAndFlushWith`. A bodiless request
 * (the inserter calls `setComplete` only) leaves the capture at zero bytes, and the field is omitted.
 *
 * The publisher SPECIALIZATION is preserved: a `Mono` body stays a `Mono` (single-buffer requests take
 * the connector's optimized path), everything else becomes a `Flux` as it would anyway.
 *
 * At write time the request's `Content-Length` is known - `EncoderHttpMessageWriter` sets it for a
 * single-buffer body right before `writeWith`, the caller may have set it for a streamed one - and is
 * handed to the capture as its buffer's sizing hint ([BoundedBodyCapture.expectBytes]); a malformed
 * caller-set value is folded to unknown, not thrown.
 *
 * The connector's own retry cannot run this tee twice: Reactor Netty retries a request on a stale pooled
 * connection only while no headers were sent, and the tee runs when the body emits inside `writeWith`,
 * which is never before the headers go out (a `bodyValue` body is written together with them, a streamed
 * one after them). Probed against Reactor Netty 1.3.7 - `docs/assessment/RETRY_PROBE-2026-09-17T19-11-37.md`.
 */
internal open class CapturingClientHttpRequestDecorator(
    delegate: ClientHttpRequest,
    protected val capture: BoundedBodyCapture,
) : ClientHttpRequestDecorator(delegate) {
    override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
        expectDeclaredLength()
        return when (body) {
            is Mono -> super.writeWith(body.map { tee(capture, it) })
            else -> super.writeWith(Flux.from(body).map { tee(capture, it) })
        }
    }

    override fun writeAndFlushWith(body: Publisher<out Publisher<out DataBuffer>>): Mono<Void> {
        expectDeclaredLength()
        return super.writeAndFlushWith(
            Flux.from(body).map { inner -> Flux.from(inner).map { tee(capture, it) } },
        )
    }

    private fun expectDeclaredLength() {
        val declared =
            try {
                headers.contentLength
            } catch (e: NumberFormatException) {
                BoundedBodyCapture.UNKNOWN_LENGTH
            }
        capture.expectBytes(declared)
    }
}

/**
 * The tee for a connector request that offers ZERO-COPY file transfer (Reactor Netty's `sendfile`
 * path): the decorator keeps the [ZeroCopyHttpOutputMessage] contract, so `ResourceHttpMessageWriter`
 * still picks zero-copy for a file `Resource` body, and the bytes are COUNTED, not copied - a body that
 * never passes through user space cannot be logged, only measured. A plain [ClientHttpRequestDecorator]
 * would silently demote such uploads to buffered writes.
 */
internal class ZeroCopyCapturingClientHttpRequestDecorator(
    delegate: ClientHttpRequest,
    capture: BoundedBodyCapture,
) : CapturingClientHttpRequestDecorator(delegate, capture),
    ZeroCopyHttpOutputMessage {
    override fun writeWith(
        file: Path,
        position: Long,
        count: Long,
    ): Mono<Void> = (delegate as ZeroCopyHttpOutputMessage).writeWith(file, position, count).doOnSuccess { capture.count(count) }
}

/**
 * This request with its body inserter wrapped, so the connector's request is decorated with the tee at
 * write time - the zero-copy-preserving variant when the connector's request supports it. Everything
 * else - method, URL, headers, cookies, attributes, the `httpRequest` consumer - is copied by
 * [ClientRequest.from], so the request the connector sees is the caller's, with the body observed on
 * its way out.
 */
internal fun ClientRequest.withRequestBodyTee(capture: BoundedBodyCapture): ClientRequest {
    val inserter = body()
    return ClientRequest
        .from(this)
        .body { outputMessage, context ->
            val decorated =
                if (outputMessage is ZeroCopyHttpOutputMessage) {
                    ZeroCopyCapturingClientHttpRequestDecorator(outputMessage, capture)
                } else {
                    CapturingClientHttpRequestDecorator(outputMessage, capture)
                }
            inserter.insert(decorated, context)
        }.build()
}
