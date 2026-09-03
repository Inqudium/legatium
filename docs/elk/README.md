# Elasticsearch mapping for the `client_*` exchange-log fields

Companion text to
[`legatium-restclient-logging-fields.component-template.json`](legatium-restclient-logging-fields.component-template.json) —
the mapping of the thirteen structured fields the Legatium modules write per outbound HTTP exchange.

> **Status note.** This is **the definition, not an
> extract**: the `client_*` family is not yet part of any upstream data-stream mapping. Whoever wires
> this module into a log pipeline composes this template there **before** the first event arrives — a
> field that reaches the index unmapped is mapped dynamically, and for a body or a header that means the
> value becomes searchable — exactly what the payload fields' `index: false` is meant to prevent.
> Once an upstream mapping exists, it wins, and this file becomes an extract of it.

```bash
curl -X PUT "$ES/_component_template/legatium-restclient-logging-fields" \
     -H 'Content-Type: application/json' \
     --data-binary @legatium-restclient-logging-fields.component-template.json
```

[`ClientLogFieldTest`](https://github.com/Inqudium/legatium/blob/main/legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/ClientLogFieldTest.kt)
(and its WebClient twin) compares this template's field set against `ClientLogField.entries` at build time and fails in both
directions — a field added to the enum without a mapping, and a mapping left behind for a removed field.

## The mapping, and the access pattern each line follows

| Field | Type | `index` | `doc_values` | Access pattern |
|---|---|---|---|---|
| `client_outcome` | `keyword` | true | on | aggregate — `success` / `failure` / `timeout` / `cancelled`; decoupled from the level |
| `client_duration_ms` | `long` | true | on | **compute** — percentiles; response occupancy including the body read, not bare round-trip time |
| `client_request_method` | `keyword` | true | on | aggregate — closed set of HTTP verbs |
| `client_response_status_code` | `short` | true | on | aggregate — a numeric **label**, never summed; absent when no response arrived |
| `client_url_host` | `keyword` | true | on | aggregate — the peer, `host` or `host:port`; "which dependency is slow" |
| `client_url_template` | `keyword` | true | on | aggregate — the URI template, parametrised, so it repeats |
| `client_url_path` | `keyword` | true | **off** | filter exactly — resolved ids, repetition factor ≈ 1 |
| `client_url_query` | `keyword` | true | **off** | filter exactly |
| `client_slow` | `boolean` | true | on | aggregate — present only when the slow threshold was reached |
| `client_request_headers` | `keyword` | **false** | off | display only — selection and masking happen in code |
| `client_response_headers` | `keyword` | **false** | off | display only |
| `client_request_body` | `keyword` | **false** | off | display only — bounded capture |
| `client_response_body` | `keyword` | **false** | off | display only — bounded tee capture |

The per-field rationale sits next to each constant as an `ELK:` line in
[`ClientLogFields.kt`](https://github.com/Inqudium/legatium/blob/main/legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/ClientLogFields.kt); the two
decisions most easily undone by accident — `index: false` on payload fields (sensitivity precedes
analytics) and `doc_values: false` on the high-cardinality path pair half (repetition factor) — each have their own explicit assertion in the lockstep test.

## Deliberately not in this template

The **MDC-carried fields** — `client_request_id`, `client_method`, `client_route`, and the trace keys
`traceId`/`spanId` — are absent on purpose: how MDC entries land in the document (flat, nested under
`mdc.`, renamed) is the **encoder's** decision, and mapping a guess here would break the moment a host
picks a different encoder layout. Map them where the encoder configuration lives.

## Beside the `endpoint_*` family

The sibling project [Limesium](https://github.com/Inqudium/limesium) maps its inbound `endpoint_*`
family in a template of the same shape. The two families are disjoint by prefix, so both templates
compose into one data-stream mapping without a collision — and a document carrying a client line
emitted inside a server request holds `client_*` fields beside the inbound `endpoint_*` MDC keys.

**Related:** the reference configuration in
[`../client-logging-reference.yml`](../client-logging-reference.yml) · the module READMEs:
[RestClient](https://github.com/Inqudium/legatium/blob/main/legatium-restclient-logging/README.md) · [WebClient](https://github.com/Inqudium/legatium/blob/main/legatium-webclient-logging/README.md).
