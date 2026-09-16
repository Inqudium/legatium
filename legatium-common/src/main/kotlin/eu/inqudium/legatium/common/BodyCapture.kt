package eu.inqudium.legatium.common

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.math.ceil

/**
 * How far the application consumed a RESPONSE body, as observed by a twin's tee: the `state` tag of the
 * `adapter.response.body.read` counter and therefore a twin contract ([tagValue]); the exact
 * observation points are documented on each twin's `BoundedBodyCapture` (deliberately separate
 * implementations - ADR-0003).
 *
 * The seam observes what flows through the tee, not WHY: on the reactive stack Spring's own
 * `releaseBody()` (`toBodilessEntity()`, the release of what an `exchangeToMono` handler left over)
 * subscribes and drains the body through the tee and therefore counts as [COMPLETE], with its bytes on
 * the size sample; the body skip of `bodyToMono(Void.class)` cancels after the first buffer and counts
 * as [PARTIAL]. On the blocking stack the same `toBodilessEntity()` never opens the stream and counts
 * as [UNREAD]. The counter's question - is a call site discarding payload it paid for? - is therefore
 * answered per ROUTE, against how that route's client is written, not across the two stacks.
 *
 * The REQUEST side has no read state: a client writes its request body in full before the peer answers,
 * so what the tee counted there is what went out.
 */
enum class BodyReadState(
    /**
     * The value of the `state` tag on the `adapter.response.body.read` counter - a contract with
     * every dashboard keying on it.
     */
    val tagValue: String,
) {
    /**
     * A body the response carried was never read/subscribed to - the bytes the peer sent never reached
     * the application (a response closed without opening its body on the blocking stack).
     */
    UNREAD("unread"),

    /**
     * Consumption started but the end of the stream was not observed - an early-exiting parser, an
     * exception or cancellation mid-read.
     */
    PARTIAL("partial"),

    /**
     * The end of the stream was observed - or the response carried no body at all (a 1xx, 204 or 304
     * answer, a declared length of zero, the answer to a HEAD): nothing to consume, nothing an
     * application could have discarded, so both twins count it complete rather than letting every
     * bodiless route look like discarded payload.
     */
    COMPLETE("complete"),
}

/**
 * Decodes a byte-bounded PREFIX of a text - the first [length] bytes of [bytes]: the capture limit bounds
 * bytes, not characters, so the cut can fall inside a multi-byte sequence; decoded as a whole, that
 * incomplete tail would render as a replacement character and corrupt the logged prefix.
 * Decoding with `endOfInput = false` leaves an incomplete trailing sequence undecoded (underflow) instead
 * of reporting it as malformed; malformed bytes INSIDE the prefix are still replaced, as `String(bytes,
 * charset)` would. Shared by both adapter-logging twins (ADR-0003).
 */
internal fun decodeTruncated(
    bytes: ByteArray,
    charset: Charset,
    length: Int = bytes.size,
): String {
    val decoder =
        charset
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
    // Sized in double precision and rounded UP: maxCharsPerByte is a float, and a float product
    // truncated to Int can undershoot for large captures - the OVERFLOW result below is the guard
    // against a decoder whose declared maximum is wrong, not the normal path.
    var capacity = ceil(length.toDouble() * decoder.maxCharsPerByte()).toInt() + 1
    val input = ByteBuffer.wrap(bytes, 0, length)
    while (true) {
        val chars = CharBuffer.allocate(capacity)
        input.rewind()
        val result = decoder.reset().decode(input, chars, false)
        if (!result.isOverflow) {
            chars.flip()
            return chars.toString()
        }
        capacity *= 2
    }
}
