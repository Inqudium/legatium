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
 * The reactive tee: every [DataBuffer] that flows is COUNTED in full, at most the capture's remaining
 * capacity is copied out of it (a non-advancing read - the read position stays untouched), and the
 * ORIGINAL buffer continues downstream unchanged - ownership, pooling and release semantics are exactly
 * those of an undecorated exchange (the reactive counterpart of the RestClient module's tee stream: a
 * passive copy, never a pre-read or replay). Transient allocation is bounded by
 * [ClientLoggingProperties.maxBodyBytes], not by the buffer size, and count-only captures (limit 0)
 * copy nothing at all.
 */
internal fun tee(
    capture: BoundedBodyCapture,
    buffer: DataBuffer,
): DataBuffer {
    val length = buffer.readableByteCount()
    val wanted = minOf(length, capture.remainingCapacity())
    if (wanted > 0) {
        val prefix = ByteArray(wanted)
        buffer.toByteBuffer(buffer.readPosition(), ByteBuffer.wrap(prefix), 0, wanted)
        capture.capture(prefix, 0, wanted)
        capture.count(length - wanted)
    } else {
        capture.count(length)
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
 */
internal open class CapturingClientHttpRequestDecorator(
    delegate: ClientHttpRequest,
    protected val capture: BoundedBodyCapture,
) : ClientHttpRequestDecorator(delegate) {
    override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> =
        when (body) {
            is Mono -> super.writeWith(body.map { tee(capture, it) })
            else -> super.writeWith(Flux.from(body).map { tee(capture, it) })
        }

    override fun writeAndFlushWith(body: Publisher<out Publisher<out DataBuffer>>): Mono<Void> =
        super.writeAndFlushWith(
            Flux.from(body).map { inner -> Flux.from(inner).map { tee(capture, it) } },
        )
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
