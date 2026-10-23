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
 * `DataBuffer`), and both directions call it only through [teeOrPass] - so the order makes a copy that
 * throws cost the logged text of that chunk, never the size sample. Transient allocation is bounded by
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
 * The fail-open [tee] both directions share - the request decorators below and [ObservedBody]'s
 * response delivery. Safety: a tee that throws is handed to [onFailure] (the filter counts it as
 * `stage=wiring`) and the ORIGINAL [buffer] is returned untouched; escaping, it would become the error
 * signal of the connector's body write or of the caller's body read, and fail the call it describes.
 */
internal fun teeOrPass(
    capture: BoundedBodyCapture,
    buffer: DataBuffer,
    onFailure: (Exception) -> Unit,
): DataBuffer =
    try {
        tee(capture, buffer)
    } catch (e: Exception) {
        onFailure(e)
        buffer
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
 * At write time the request's `Content-Length` is known and is handed to the capture as its buffer's
 * sizing hint ([BoundedBodyCapture.expectBytes]); a malformed caller-set value is folded to unknown, not
 * thrown. Assumption: `EncoderHttpMessageWriter` sets the header for a single-buffer body right before
 * `writeWith`, and only the caller sets it for a streamed one (probed on Spring Framework 7.0.9).
 *
 * Every buffer goes through [teeOrPass]: a copy that throws reaches `onTeeFailure` and costs the logged
 * text of that buffer, never the bytes the connector writes; the filter passes its counting reporter.
 *
 * Assumption: the connector's own retry cannot run this tee twice (probed on Reactor Netty 1.3.7) -
 * Reactor Netty retries a request on a stale pooled connection only while no headers were sent, and the
 * tee runs when the body emits inside `writeWith`, which is never before the headers go out (a
 * `bodyValue` body is written together with them, a streamed one after them), so a retried attempt is
 * the FIRST run of the tee. The derivation and the probe are the module guide's section "Retries yield
 * one line per attempt".
 */
internal open class CapturingClientHttpRequestDecorator(
    delegate: ClientHttpRequest,
    protected val capture: BoundedBodyCapture,
    private val onTeeFailure: (Exception) -> Unit,
) : ClientHttpRequestDecorator(delegate) {
    override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
        expectDeclaredLength()
        return when (body) {
            is Mono -> super.writeWith(body.map { observed(it) })
            else -> super.writeWith(Flux.from(body).map { observed(it) })
        }
    }

    override fun writeAndFlushWith(body: Publisher<out Publisher<out DataBuffer>>): Mono<Void> {
        expectDeclaredLength()
        return super.writeAndFlushWith(
            Flux.from(body).map { inner -> Flux.from(inner).map { observed(it) } },
        )
    }

    private fun observed(buffer: DataBuffer): DataBuffer = teeOrPass(capture, buffer, onTeeFailure)

    private fun expectDeclaredLength() = capture.expectBytes(BoundedBodyCapture.declaredLength { headers.contentLength })
}

/**
 * The tee for a connector request that offers ZERO-COPY file transfer (Reactor Netty's `sendfile`
 * path): the decorator keeps the [ZeroCopyHttpOutputMessage] contract, and the bytes are COUNTED, not
 * copied - a body that never passes through user space cannot be logged, only measured. Assumption:
 * `ResourceHttpMessageWriter` picks zero-copy for a file `Resource` body only when the output message
 * is a [ZeroCopyHttpOutputMessage] (probed on Spring Framework 7.0.9). Rationale: not a plain
 * [ClientHttpRequestDecorator], because it would silently demote such uploads to buffered writes. The
 * count lands on the connector's SUCCESS signal: a transfer that fails counts nothing - the connector
 * reports no partial count, and the size meter describes bytes that flowed, not bytes that were
 * attempted (pinned in `CapturingDecoratorsTest`).
 */
internal class ZeroCopyCapturingClientHttpRequestDecorator(
    delegate: ClientHttpRequest,
    capture: BoundedBodyCapture,
    onTeeFailure: (Exception) -> Unit,
) : CapturingClientHttpRequestDecorator(delegate, capture, onTeeFailure),
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
 * its way out. [onTeeFailure] receives a copy that threw ([CapturingClientHttpRequestDecorator]).
 */
internal fun ClientRequest.withRequestBodyTee(
    capture: BoundedBodyCapture,
    onTeeFailure: (Exception) -> Unit,
): ClientRequest {
    val inserter = body()
    return ClientRequest
        .from(this)
        .body { outputMessage, context ->
            val decorated =
                if (outputMessage is ZeroCopyHttpOutputMessage) {
                    ZeroCopyCapturingClientHttpRequestDecorator(outputMessage, capture, onTeeFailure)
                } else {
                    CapturingClientHttpRequestDecorator(outputMessage, capture, onTeeFailure)
                }
            inserter.insert(decorated, context)
        }.build()
}
