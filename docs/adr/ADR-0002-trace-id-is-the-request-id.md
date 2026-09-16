# ADR-0002: The trace id is the request id; the correlation header is sent only on traceless calls

**Status:** Accepted  
**Date:** 2026-09-03  
**Last updated:** 2026-09-04  
**Deciders:** Dirk Haase (maintainer)  
**Related:** ADR-0003 (`Traceparent`, `MdcScope` and
`CorrelationHeader` live in `legatium-common` by its criterion),
ADR-0004 (the generator that produces the id on traceless calls),
ADR-0008 (`adapter.logging.correlation.id` watches this contract)

## Context

A client logger must be observationally neutral: whether outbound
logging is enabled or disabled must not change the HTTP communication
a peer sees. At the same time an outbound call needs an identity that
joins three things: the client line, the application's own log lines
around the call, and the peer's server-side line.

The host's tracing propagation already writes the strongest such
identity onto every outgoing request: the W3C `traceparent` header,
whose trace id is shared with the peer's server span and whose
parent-id IS the local client span.

The sibling project limesium settled the inbound side of this question
in its ADR-0002 (the trace id is the request id; the
`X-Correlation-Id` echo happens only on traceless exchanges). This ADR
is its mirror image for the outbound side, decided BEFORE the first
line of code so that both twins follow it from the start.

## Decision

**Both twins source the trace id from the outgoing `traceparent`
header, the trace id doubles as the request id, and a correlation
header is added to the request only on traceless calls that carry
none.**

1. **Trace id from the header, in both twins.** The `traceparent` the
   host's propagation put on the request (Micrometer Tracing through
   the client observation, which runs BEFORE interceptors and filter
   functions; pinned beside a real Brave bridge by the tracing
   integration tests) is parsed with the strict W3C validation shared
   with limesium. The header's trace id is published as `traceId`; its
   parent-id is the local client span the peer will treat as its
   parent, and is published as `spanId`, Boot's local-span key, because
   on the outbound side that IS the local span of the call. (Inbound,
   limesium publishes the same field as `parentSpanId`, because there
   it is the caller's span; the two projects are consistent, not
   identical.) A `traceparent` that fails W3C validation counts as
   absent.
2. **An available trace id is the request id.** When the outgoing
   `traceparent` is conformant, `adapter_request_id` carries its trace
   id. A correlation header the caller put on the request is ignored
   on such calls: the distributed identity outranks the private one.
3. **A correlation id is generated only on traceless calls.** When no
   (valid) `traceparent` is present, a correlation header already on
   the request is accepted if it passes the acceptance rule below;
   otherwise a new id is generated.
4. **The header is added only when generated.** A traceless call that
   already carries an acceptable header goes out as the caller built
   it; a traceless call without one gets the generated id ADDED under
   the configured header name, so the peer can quote it: the outbound
   counterpart of the inbound echo. When a `traceparent` header is
   present, the module adds nothing: the call goes out observationally
   untouched.
5. **The MDC always carries a request id.** In every case, trace-derived
   or generated, the winning id is set as the `adapter_request_id` MDC
   value (`MdcKeys.REQUEST_ID`) in both twins, around the emission and
   (blocking twin) around the wire call, as an ADDITIVE overlay: an
   inbound request's `endpoint_request_id` or a bridge's keys on the
   thread stay in place. Neutrality is a wire property; inside the
   process the identity is unconditional.

### The acceptance rule for a propagated correlation id

A correlation id found on a traceless request typically originates
outside the application (an inbound request propagated onto the
outbound call) and lands verbatim in the message, the MDC and, when
selected, the header field of every line of the call. It is therefore
bounded the way the URI already is (`CorrelationHeader` in
`legatium-common`, both twins): at most 200 characters, visible ASCII
only (`0x21`..`0x7E`; no whitespace, no control characters, no
non-ASCII). A value outside the rule is treated as ABSENT: the twin
generates its own id, SENDS it in place of the unacceptable value, and
counts the call as `generated`. Legitimate ids (UUIDs, base-36 ids,
ids with the usual punctuation) are unaffected. Limesium applies the
same shape of rule on the inbound side (`CorrelationHeaderValue`),
but with a bound of **128** characters since its commit `a9f992c` of
2026-09-05 - the pair is NOT consistent between 129 and 200
characters: an id of that length propagated onto an outbound call is
accepted and sent by legatium and replaced (`generated`) by a
limesium peer. Neither build can see the other's constant, so each
repository pins its own bound as a literal in its tests
(`CorrelationHeaderTest`; limesium's `CorrelationHeaderValueTest`) -
a change on either side is a visible decision. Whether to align the
two bounds is open (comment audit of 2026-09-16, finding 18).

**Implementation order:** this ADR records the contract first; the
code follows it. The implementation lands in lockstep across both
twins: the shared `Traceparent` parser and `MdcScope` (ADR-0003), the
interceptor and filter wiring, the metrics
(`correlation.id{source=trace|header|generated}`), the guides,
`adapter-logging-reference.yml`, the READMEs, and the test suites.

## Consequences

**Positive:**

- **Traced calls are neutral.** With a conformant `traceparent` the
  module adds no header and invents no identity; enabling or disabling
  the logger is invisible to the peer.
- **The inbound and outbound lines join by construction.** A limesium
  server line and a legatium client line of the same trace carry the
  same `traceId` and the same request id; without tracing they join by
  the MDC overlay instead (the client line inherits
  `endpoint_request_id`).
- **In a host with tracing configured, every call is traced.** The
  client observation roots a trace when none is active, so
  `traceparent` is on every request (sampled or not) and the module
  never generates an id there; the
  `adapter.logging.correlation.id{source=generated}` counter then
  reads zero by construction, not by regression. Pinned by test.

**Negative:**

- **`adapter_request_id` changes cardinality on traced calls.** All
  calls under one trace share the request id, because it IS the trace
  id; per-call uniqueness is only guaranteed for self-generated ids.
  Per-call lines remain distinguishable by their remaining fields, and
  `spanId` is unique per call.
- A propagated id that violates the acceptance rule is replaced on the
  wire, so the peer sees a different id than the application intended
  to forward; the `generated` count is the visible trace of that.

**Neutral:**

- The traceless header remains a deliberate, documented service to
  peers that have no tracing infrastructure, and is the one visible
  effect of enabling the logger.

## History

- **2026-09-03:** decided before the first line of code, as the
  outbound mirror of limesium's ADR-0002.
- **2026-09-04:** the acceptance rule for a propagated correlation id
  was added per finding 13 of
  `docs/assessment/CODE_ANALYSIS-2026-09-04T20-56-15.md`; step 3 had
  adopted the header value verbatim.
- **2026-09-16:** the acceptance rule's claim that limesium mirrors it
  "so the pair stays consistent" was corrected: limesium bounds inbound
  ids at 128 characters, this module at 200 (comment audit of
  2026-09-16, finding 18). The bound is now pinned as a literal in
  `CorrelationHeaderTest`.
