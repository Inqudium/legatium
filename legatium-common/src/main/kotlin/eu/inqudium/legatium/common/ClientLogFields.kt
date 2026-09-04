package eu.inqudium.legatium.common

import org.slf4j.spi.LoggingEventBuilder
import kotlin.reflect.KClass

/**
 * The structured log fields of an OUTBOUND HTTP exchange: their wire names, and the one rendering each
 * name is allowed to carry. ONE enum for both twins (ADR-0003): the family is a cross-stack contract,
 * and the twins' copies were byte-identical - the RestClient interceptor and the WebClient filter emit
 * the same thirteen fields under the same names with the same shapes; only the VALUE vocabulary of
 * [OUTCOME] is wider on the reactive stack (`cancelled`), which is a property of the value, not of the
 * field.
 *
 * These names are a CONTRACT with the log index, not local identifiers: renaming a constant below is free,
 * changing a [wireName] breaks every dashboard, saved search and alert rule keying on it. The mapping is
 * shipped as the repository-shared `/docs/elk/legatium-restclient-logging-fields.component-template.json` -
 * the DEFINITION of the family (no upstream data-stream mapping exists yet), composed into the log pipeline
 * by whoever wires a module in; `ClientLogFieldTest` keeps enum and template in lockstep, build-breaking
 * in both directions.
 *
 * **Why an enum rather than string literals.** A literal repeated across call sites is a typo away from a
 * second, near-identical field no dashboard knows about; and beyond the name, a field declares its wire
 * SHAPE (the JVM type the index template maps), pinned by the lockstep test.
 *
 * The `adapter_` prefix keeps the family disjoint from the `endpoint_*` family the sibling project
 * limesium writes for INBOUND exchanges: one document may carry both (a client line emitted inside a
 * server request inherits the ambient `endpoint_*` MDC), and no field may mean two things.
 *
 * ELK MAPPING. Each field carries an `ELK:` line stating the intended mapping and the access pattern that
 * earns it:
 *
 *   aggregate / sort  -> keyword or numeric   index true    doc_values ON
 *   filter exactly    -> keyword              index true    doc_values off
 *   display only      -> text or keyword      index FALSE   doc_values off
 *   compute           -> long or double       index true    doc_values ON
 *
 * Headers and bodies are display-only (`index: false`): they are the widest data-leak surface of the
 * family, and a value that reaches the log should at least not be searchable for deliberately.
 */
internal enum class ClientLogField(
    val wireName: String,
    /** The exact JVM type a value of this field must have on the wire - asserted, never converted. */
    private val type: KClass<out Any>,
) {
    /**
     * ELK: `keyword`, index true, doc_values ON - aggregate. `success`, `failure` and `timeout` on both
     * stacks, plus `cancelled` on the reactive one (a cancelled subscription is the reactive reality a
     * blocking call cannot have), and the field a dashboard splits by - deliberately NOT the log level:
     * a 5xx answer logs at WARN while a call that threw logs at ERROR, yet both carry `failure`. Panels
     * key off this field, the level only carries severity.
     */
    OUTCOME("adapter_outcome", String::class),

    /**
     * ELK: `long`, index true, doc_values ON - compute (percentiles). Milliseconds, with the unit in the
     * name; measured from the injected monotonic [NanoTimeSource], so it is a duration, never a timestamp.
     * Measured until the exchange is truly over - the response closed (RestClient) or its body's terminal
     * signal (WebClient): response occupancy including the body read, not bare round-trip time.
     */
    DURATION_MS("adapter_duration_ms", Long::class),

    /**
     * ELK: `keyword`, index true, doc_values ON - aggregate. Normally a handful of well-known HTTP
     * methods, but NOT a closed vocabulary: RFC 9110 keeps the method token extensible, and the field
     * carries whatever token the request line named.
     */
    REQUEST_METHOD("adapter_request_method", String::class),

    /**
     * ELK: **`short`**, index true, doc_values ON - aggregate, NOT compute: a numeric LABEL one groups by
     * and never averages. `short` is safe because HTTP status codes are three digits.
     *
     * ABSENT when the call produced no response at all (connection refused, a timeout before the status
     * line, a cancellation before the response arrived) - the message then shows `-> -`, and [OUTCOME]
     * is the authoritative disposition.
     */
    RESPONSE_STATUS_CODE("adapter_response_status_code", Int::class),

    /**
     * ELK: `keyword`, index true, doc_values ON - aggregate. The peer's host, with the port when the URI
     * names one explicitly (`api.example.com`, `localhost:8081`) - the coordinate an inbound exchange
     * does not have and an outbound one cannot do without: "which dependency is slow" is a question
     * about this field.
     */
    URL_HOST("adapter_url_host", String::class),

    /**
     * ELK: `keyword`, index true, doc_values ON - aggregate. The low-cardinality URI template
     * (`/api/things/{id}`) the client recorded for the request when the caller used the template form of
     * `uri(...)` - the aggregation half of the pair. Absent when the caller passed an expanded `URI`.
     */
    URL_TEMPLATE("adapter_url_template", String::class),

    /**
     * ELK: `keyword`, index true, **doc_values OFF** - filter exactly. The expanded request path, ids and
     * all: useful for finding one call, useless to group by (singleton buckets), expensive to keep an
     * ordinal dictionary for.
     */
    URL_PATH("adapter_url_path", String::class),

    /**
     * ELK: `keyword`, index true, doc_values OFF - filter exactly, as [URL_PATH]. Its own field rather
     * than part of the path so grouping by path is not defeated by varying query strings.
     */
    URL_QUERY("adapter_url_query", String::class),

    /**
     * ELK: `boolean`, index true, doc_values ON - aggregate. True when the exchange reached the
     * configured slow-request threshold; present only then, so absence means fast.
     */
    SLOW("adapter_slow", Boolean::class),

    /**
     * ELK: `keyword`, **index FALSE**, doc_values off - display only. Read in the hit, never searched,
     * never grouped. Only the configured header selection reaches this field at all, and names listed as
     * masked carry a stable `length:hash` fingerprint instead of the value.
     */
    REQUEST_HEADERS("adapter_request_headers", String::class),

    /** ELK: `keyword`, index false, doc_values off - display only, as [REQUEST_HEADERS]. */
    RESPONSE_HEADERS("adapter_response_headers", String::class),

    /**
     * ELK: `keyword`, **index FALSE**, doc_values off - display only. The largest field of the family and
     * the widest data-leak surface, which is why it exists only when body capture is enabled and carries
     * at most the configured capture limit.
     */
    REQUEST_BODY("adapter_request_body", String::class),

    /** ELK: `keyword`, index false, doc_values off - display only, as [REQUEST_BODY]. */
    RESPONSE_BODY("adapter_response_body", String::class),

    ;

    /**
     * Whether [value] has the exact JVM type this field puts on the wire - the shape the index template
     * maps. Consulted by `ClientLogFieldTest` for the lockstep pin, not at emission: the emitters are the
     * only callers of [addKeyValue] and pass values of the declared types, so a runtime gate over the
     * module's own code guarded a case the tests already exclude (architecture review of 2026-09-04).
     */
    fun accepts(value: Any?): Boolean = value != null && type.isInstance(value)
}

/**
 * Adds a field to a log event under its wire name. The overload exists so a call site names the field
 * rather than a string, and cannot reach the event with a misspelled key.
 */
internal fun LoggingEventBuilder.addKeyValue(
    field: ClientLogField,
    value: Any?,
): LoggingEventBuilder = addKeyValue(field.wireName, value)

/** As [addKeyValue], but leaves the field off the event when [value] is null - for optional fields in a single builder chain. */
internal fun LoggingEventBuilder.addKeyValueIfPresent(
    field: ClientLogField,
    value: Any?,
): LoggingEventBuilder = if (value == null) this else addKeyValue(field, value)

/** Attaches [cause] when there is one; otherwise returns the builder unchanged, so the chain stays a single expression. */
internal fun LoggingEventBuilder.setCauseIfPresent(cause: Throwable?): LoggingEventBuilder = if (cause == null) this else setCause(cause)
