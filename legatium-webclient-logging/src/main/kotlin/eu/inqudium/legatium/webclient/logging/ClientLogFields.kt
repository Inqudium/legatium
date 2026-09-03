package eu.inqudium.legatium.webclient.logging

import eu.inqudium.legatium.common.NanoTimeSource
import org.slf4j.LoggerFactory
import org.slf4j.spi.LoggingEventBuilder
import kotlin.reflect.KClass

/**
 * The structured log fields of an OUTBOUND HTTP exchange: their wire names, and the one rendering each
 * name is allowed to carry. The REACTIVE twin of the enum in legatium-restclient-logging, which OWNS the
 * family: the wire names are a contract with the log index, the mapping is the repository-shared
 * `/docs/elk/` component template, and the design rationale (why an enum, the `ELK:` access-pattern
 * vocabulary, why headers and bodies are display-only) is documented once on that enum. This module's
 * `ClientLogFieldTest` reads the template across the reactor and keeps this enum in lockstep with it,
 * build-breaking in both directions - so the per-field `ELK:` lines below are tested, not asserted.
 *
 * [format] converts nothing - every field goes on the wire exactly as supplied - it only GUARANTEES the
 * type. Stack-specific here: the `cancelled` outcome.
 */
internal enum class ClientLogField(
    val wireName: String,
    /** The exact JVM type a value of this field must have on the wire - asserted, never converted. */
    private val type: KClass<out Any>,
) {
    /**
     * ELK: `keyword`, index true, doc_values ON - aggregate. Four values on this stack (`success`,
     * `failure`, `timeout`, `cancelled` - the reactive disposition vocabulary, `cancelled` being the
     * one the blocking twin cannot have), and the field a dashboard splits by - deliberately NOT the
     * log level: a 5xx answer logs at WARN while a call that errored logs at ERROR, yet both carry
     * `failure`. Panels key off this field, the level only carries severity.
     */
    OUTCOME("client_outcome", String::class),

    /**
     * ELK: `long`, index true, doc_values ON - compute (percentiles). Milliseconds, with the unit in the
     * name; measured from the injected monotonic [NanoTimeSource], so it is a duration, never a timestamp.
     * Measured until the response body's terminal signal - response occupancy including the body
     * read, not bare round-trip time.
     */
    DURATION_MS("client_duration_ms", Long::class),

    /**
     * ELK: `keyword`, index true, doc_values ON - aggregate. Normally a handful of well-known HTTP
     * methods, but NOT a closed vocabulary: RFC 9110 keeps the method token extensible, and the field
     * carries whatever token the request line named.
     */
    REQUEST_METHOD("client_request_method", String::class),

    /**
     * ELK: **`short`**, index true, doc_values ON - aggregate, NOT compute: a numeric LABEL one groups by
     * and never averages. `short` is safe because HTTP status codes are three digits.
     *
     * ABSENT when the call produced no response at all (connection refused, a timeout before the status
     * line, a cancellation before the response arrived) - the message then shows `-> -`, and [OUTCOME]
     * is the authoritative disposition.
     */
    RESPONSE_STATUS_CODE("client_response_status_code", Int::class),

    /**
     * ELK: `keyword`, index true, doc_values ON - aggregate. The peer's host, with the port when the URI
     * names one explicitly (`api.example.com`, `localhost:8081`) - the coordinate an inbound exchange
     * does not have and an outbound one cannot do without: "which dependency is slow" is a question
     * about this field.
     */
    URL_HOST("client_url_host", String::class),

    /**
     * ELK: `keyword`, index true, doc_values ON - aggregate. The low-cardinality URI template
     * (`/api/things/{id}`) the client recorded for the request when the caller used the template form of
     * `uri(...)` - the aggregation half of the pair. Absent when the caller passed an expanded `URI`.
     */
    URL_TEMPLATE("client_url_template", String::class),

    /**
     * ELK: `keyword`, index true, **doc_values OFF** - filter exactly. The expanded request path, ids and
     * all: useful for finding one call, useless to group by (singleton buckets), expensive to keep an
     * ordinal dictionary for.
     */
    URL_PATH("client_url_path", String::class),

    /**
     * ELK: `keyword`, index true, doc_values OFF - filter exactly, as [URL_PATH]. Its own field rather
     * than part of the path so grouping by path is not defeated by varying query strings.
     */
    URL_QUERY("client_url_query", String::class),

    /**
     * ELK: `boolean`, index true, doc_values ON - aggregate. True when the exchange reached the
     * configured slow-request threshold; present only then, so absence means fast.
     */
    SLOW("client_slow", Boolean::class),

    /**
     * ELK: `keyword`, **index FALSE**, doc_values off - display only. Read in the hit, never searched,
     * never grouped. Only the configured header selection reaches this field at all, and names listed as
     * masked carry a stable `length:hash` fingerprint instead of the value.
     */
    REQUEST_HEADERS("client_request_headers", String::class),

    /** ELK: `keyword`, index false, doc_values off - display only, as [REQUEST_HEADERS]. */
    RESPONSE_HEADERS("client_response_headers", String::class),

    /**
     * ELK: `keyword`, **index FALSE**, doc_values off - display only. The largest field of the family and
     * the widest data-leak surface, which is why it exists only when body capture is enabled and carries
     * at most the configured capture limit.
     */
    REQUEST_BODY("client_request_body", String::class),

    /** ELK: `keyword`, index false, doc_values off - display only, as [REQUEST_BODY]. */
    RESPONSE_BODY("client_response_body", String::class),

    ;

    /** Where a rejected value is reported, since [addKeyValue] swallows the rejection rather than propagating it. */
    val log = LoggerFactory.getLogger(ClientLogField::class.java)

    /**
     * The exact shape this field puts on the wire: [value] itself with its type asserted - see the class
     * comment. No conversion, by design: a value of the wrong type is rejected, never coerced.
     */
    fun format(value: Any?): Any =
        if (value != null && type.isInstance(value)) {
            value
        } else {
            throw IllegalArgumentException(
                "Structured log field $wireName expects ${type.simpleName}, got ${value?.let { it::class.simpleName } ?: "null"}",
            )
        }
}

/**
 * Adds a field to a log event under its wire name, rendered by the field itself. The overload exists so a
 * call site names the field rather than a string, and cannot reach the event with an unrendered value by
 * spelling the key by hand.
 *
 * A rejected value costs THIS FIELD and a warning naming it, never the log call it was part of: the
 * exchange line is the observability of the outbound call, and letting a type slip take the whole
 * statement down would remove it exactly when it is needed.
 */
internal fun LoggingEventBuilder.addKeyValue(
    field: ClientLogField,
    value: Any?,
): LoggingEventBuilder =
    try {
        addKeyValue(field.wireName, field.format(value))
    } catch (e: IllegalArgumentException) {
        field.log.warn(e.toString())
        this
    }

/** As [addKeyValue], but leaves the field off the event when [value] is null - for optional fields in a single builder chain. */
internal fun LoggingEventBuilder.addKeyValueIfPresent(
    field: ClientLogField,
    value: Any?,
): LoggingEventBuilder = if (value == null) this else addKeyValue(field, value)

/** Attaches [cause] when there is one; otherwise returns the builder unchanged, so the chain stays a single expression. */
internal fun LoggingEventBuilder.setCauseIfPresent(cause: Throwable?): LoggingEventBuilder = if (cause == null) this else setCause(cause)
