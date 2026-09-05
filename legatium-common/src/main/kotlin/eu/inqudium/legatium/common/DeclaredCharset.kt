package eu.inqudium.legatium.common

import org.springframework.http.HttpHeaders
import org.springframework.http.InvalidMediaTypeException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * The charset the `Content-Type` declares, UTF-8 when there is none or the media type does not parse -
 * a malformed header is the peer's (or the caller's) problem and must not cost the log line. One
 * definition for the request side (wiring) and the response side (emission), shared by both twins
 * (ADR-0003).
 */
internal fun HttpHeaders.declaredCharsetOrUtf8(): Charset =
    try {
        contentType?.charset
    } catch (e: InvalidMediaTypeException) {
        null
    } ?: StandardCharsets.UTF_8
