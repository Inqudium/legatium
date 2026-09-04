# ADR-0002: The trace id is the request id; the correlation header is sent only on traceless calls

- **Status:** accepted
- **Date:** 2026-09-03
- **Context:** A client logger must be observationally neutral: whether
  outbound logging is enabled or disabled must not change the HTTP
  communication a peer sees. At the same time an outbound call needs an
  identity that joins three things - the client line, the application's
  own log lines around the call, and the peer's server-side line - and
  the host's tracing propagation already writes the strongest such
  identity onto every outgoing request: the W3C `traceparent` header,
  whose trace id is shared with the peer's server span and whose
  parent-id IS the local client span. The sibling project limesium
  settled the inbound side of this question in its ADR-0002 (the trace
  id is the request id; the `X-Correlation-Id` echo happens only on
  traceless exchanges). This ADR is its mirror image for the outbound
  side, decided BEFORE the first line of code so that both twins follow
  it from the start.

## Decision

**Both twins source the trace id from the outgoing `traceparent`
header, the trace id doubles as the request id, and a correlation header
is added to the request only on traceless calls that carry none:**

1. **Trace id from the header, in both twins.** The `traceparent` the
   host's propagation (Micrometer Tracing through the client
   observation, which runs BEFORE interceptors and filter functions -
   pinned beside a real Brave bridge by the tracing integration tests)
   put on the request is parsed with the strict W3C validation shared
   with limesium. The header's trace id is published as `traceId`; its
   parent-id is the local client span the peer will treat as its parent,
   and is published as `spanId` - Boot's local-span key - because on the
   outbound side that IS the local span of the call. (Inbound, limesium
   publishes the same field as `parentSpanId`, because there it is the
   caller's span; the two projects are consistent, not identical.) A
   `traceparent` that fails W3C validation counts as absent.
2. **An available trace id is the request id.** When the outgoing
   `traceparent` is conformant, `adapter_request_id` carries its trace
   id. A correlation header the caller put on the request is ignored on
   such calls: the distributed identity outranks the private one.
3. **A correlation id is generated only on traceless calls.** When no
   (valid) `traceparent` is present, a correlation header already on the
   request is accepted; otherwise a new id is generated.
4. **The header is added only when generated.** A traceless call that
   already carries the header goes out as the caller built it; a
   traceless call without one gets the generated id ADDED under the
   configured header name, so the peer can quote it - the outbound
   counterpart of the inbound echo. When a `traceparent` header is
   present, the module adds nothing: the call goes out observationally
   untouched.
5. **The MDC always carries a request id.** In every case - trace-derived
   or generated - the winning id is set as the `adapter_request_id` MDC
   value (`MdcKeys.REQUEST_ID`) in both twins, around the emission and
   (blocking twin) around the wire call, as an ADDITIVE overlay: an
   inbound request's `endpoint_request_id` or a bridge's keys on the
   thread stay in place. Neutrality is a wire property; inside the
   process the identity is unconditional.

## Consequences

- **Traced calls are neutral.** With a conformant `traceparent` the
  module adds no header and invents no identity - enabling or disabling
  the logger is invisible to the peer. The traceless header remains a
  deliberate, documented service to peers that have no tracing
  infrastructure, and is the one visible effect.
- **In a host with tracing configured, every call is traced.** The client
  observation roots a trace when none is active, so `traceparent` is on
  every request (sampled or not) and the module never generates an id
  there; the `adapter.logging.correlation.id{source=generated}` counter
  then reads zero by construction, not by regression. Pinned by test.
- **`adapter_request_id` changes cardinality on traced calls.** All calls
  under one trace share the request id, because it IS the trace id; per-
  call uniqueness is only guaranteed for self-generated ids. Per-call
  lines remain distinguishable by their remaining fields, and `spanId`
  is unique per call.
- **The inbound and outbound lines join by construction.** A limesium
  server line and a legatium client line of the same trace carry the
  same `traceId` and the same request id; without tracing they join by
  the MDC overlay instead (the client line inherits
  `endpoint_request_id`).
- Implementation follows in lockstep across both twins - the shared
  `Traceparent` parser and `MdcScope` (ADR-0003), the interceptor and
  filter wiring, the metrics (`correlation.id{source=trace|header|
  generated}`), the GUIDEs, `adapter-logging-reference.yml`, READMEs, and
  the test suites. This ADR records the contract first; the code follows
  it.

## Amendment (2026-09-04): the accepted shape of a propagated correlation id

Step 3 - a traceless call accepts the correlation id already on the request
- adopted the header value verbatim. The value typically originates outside
the application (an inbound request propagated onto the outbound call) and
lands verbatim in the message, the MDC and, when selected, the header field
of every line of the call, so it is now bounded the way the URI already was
(`CorrelationHeader` in `legatium-common`, both twins): at most 200
characters, visible ASCII only (`0x21`..`0x7E` - no whitespace, no control
characters, no non-ASCII). A value outside the rule is treated as ABSENT: the
twin generates its own id, SENDS it in place of the unacceptable value, and
counts the call as `generated`. Legitimate ids - UUIDs, base-36 ids, ids
with the usual punctuation - are unaffected. The sibling project limesium
mirrors the rule on the inbound side so the pair stays consistent.
