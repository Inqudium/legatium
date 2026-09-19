# ADR-0012: A 4xx answer is `rejected`; the level is INFO, WARN for 401, 403, 408 and 429

**Status:** Accepted  
**Date:** 2026-09-19  
**Deciders:** Dirk Haase (maintainer)  
**Related:** ADR-0006 (the body gate, whose 4xx special case this
ADR removes), ADR-0008 (the `outcome` tag of the events counter gains
a value), ADR-0003 (`Classification.ofStatus` lives in
`legatium-common` by its criterion), ADR-0007 (`adapter` names the
place where we call a foreign party; the sibling's `endpoint` names
the place where a foreign party calls us)

## Context

The exchange line carries two independent things: the SLF4J level
and `adapter_outcome`. The level is the severity an operator's
alerting keys on; the outcome is the semantic a dashboard splits by
(Common guide §7.3). Until this decision, both twins resolved them
so: a thrown call is ERROR `failure` (WARN `timeout` when a timeout
is in the cause chain), a cancelled subscription is WARN
`cancelled`, a 5xx without an exception is WARN `failure`, and
everything else, every 4xx included, is INFO `success`. Slowness
escalates INFO to WARN without changing the outcome.

The question that recurred was whether a 4xx should not be a WARN.
The intuition is sound on the outbound side: the caller of an
adapter is our own application, so a 400 or 422 often says our
payload is wrong, a 401 or 403 that our credentials are, a 429 that
we overload the peer. Those are defects on our side, and WARN is the
level for something an operator should look at.

Two forces pull against a blanket WARN.

**A 4xx is a regular answer path, also for a client.** A GET that
asks whether a resource exists gets a 404 by design. Optimistic
locking answers 409 or 412 in the normal course of events. A peer
that validates an end user's input the application forwarded
answers 400 or 422 for that user's mistake, not ours. Raising every
4xx to WARN would mark each of these calls as a warning on every
run, and the WARN channel would stop meaning "look at this".

**The module sees the status, not the cause.** Whether a given 4xx
is our defect or the expected branch of a flow is knowledge the
application has (it receives the exception and knows the call site)
and the module has not: it observes the wire, the same boundary
ADR-0006 records for a `200` the application cannot decode.

What the old classification lacked was not severity but semantics:
`success` covered a 200 and a 404 alike, so a dashboard that wanted
the 4xx share had to query the status field. The outcome vocabulary
had `failure` for the peer's fault, `timeout` and `cancelled` for
the clock and the caller's abandonment, but no value for "the
request was refused". ADR-0006 had to widen its body gate beyond
the vocabulary by hand (`outcome != success || status in 400..499`)
for exactly that gap.

The sibling project limesium logs the inbound line with the same
vocabulary. There the same 4xx says the foreign caller made a
mistake and the application answered as designed; scanners, expired
tokens and stale links produce 401, 403, 404 and 429 all day on an
exposed API.

## Decision

**A 4xx answer is a `rejected` exchange. Its level is INFO, and WARN
for 401, 403, 408 and 429.** The outcome vocabulary is the
responsibility axis of an exchange, the level its severity; the two
stay decoupled.

### The outcome names who is responsible

| `adapter_outcome` | Meaning                                                  | Responsible                    |
|-------------------|----------------------------------------------------------|--------------------------------|
| `success`         | the peer answered below 400                              | nobody                         |
| `rejected`        | the peer answered 4xx: the request was refused           | the caller, this application   |
| `failure`         | the peer answered 5xx, or the call threw                 | the peer, or the call itself   |
| `timeout`         | our wait ended without an answer                         | the clock                      |
| `cancelled`       | the caller abandoned the subscription (reactive stack)   | the caller, deliberately       |

`rejected` is one value for the whole 4xx class, as `failure` is one
value for the whole 5xx class: the status is on the line as a
number for the finer split.

### The classification

Resolved in this order in each twin's `ExchangeLogEmitter`; the
status half is `Classification.ofStatus` in `legatium-common`, one
function for both twins (ADR-0003):

| Condition                                                   | Level          | `adapter_outcome` |
|-------------------------------------------------------------|----------------|-------------------|
| the call threw and a timeout is in the cause chain          | `WARN`         | `timeout`         |
| the call threw                                              | `ERROR`        | `failure`         |
| the subscription was cancelled (reactive stack)             | `WARN`         | `cancelled`       |
| status ≥ 500 without an exception                           | `WARN`         | `failure`         |
| status 401, 403, 408 or 429 without an exception            | `WARN`         | `rejected`        |
| any other 4xx without an exception                          | `INFO`         | `rejected`        |
| otherwise                                                   | `INFO`         | `success`         |
| … and the duration reached the slow threshold               | `INFO → WARN`  | unchanged         |

### The escalation set

The four statuses lifted to WARN are the rejections that only an
operator can resolve, because they are about the application's own
standing with the peer rather than about one request's content:

- **401 and 403:** the peer refuses this application for who it is.
  Expired or rotated credentials, a revoked scope, a client
  certificate the peer no longer trusts.
- **408:** the peer gave up waiting for the application's request.
  Not a `timeout`: `timeout` means our wait ended without an answer,
  no status, an incomplete exchange, where a 408 is a complete
  answer that says the connection or our sending was too slow.
- **429:** the peer throttles this application. A quota is exhausted
  or a rate limit is hit, which is a capacity decision on our side.

Every other 4xx says the request itself was wrong or the resource
is not there, which the application handles: 404 on a lookup, 409
or 412 of optimistic locking, 400 or 422 the peer returns for input
the application forwarded. The set is a fixed rule of the family,
pinned by `ClassificationTest`, not a property: a configurable list
would add a second place where severity is decided and would split
the family's log contract between hosts. The escalation lifts the
level only; the outcome stays `rejected`, so the meters and the
dashboards see one value per status class.

### The body gate follows

`on-failure` body logging writes the bodies when the outcome is not
`success`. A `rejected` exchange is not a success, so the 4xx
bodies stay logged (the case a body explains best, ADR-0006), and
the hand-written widening of that gate by the status range is gone:
the gate reads `outcome != success` again, in both twins.

**Scope.** This decision covers both twins here. The sibling
limesium adopted the same vocabulary the same day (its ADR-0007):
`rejected` names the same thing on the inbound line, the caller's
refused request. There the caller is the foreign party, so the
inbound `rejected` line stays INFO for every 4xx and the escalation
set does not apply; its suites pin the four statuses at INFO.

**Verification.** `ClassificationTest` in `legatium-common` pins the
table and the escalation set. Each twin's suite pins that its
emitter calls the shared function (a 404 at INFO `rejected`, the
four escalated statuses at WARN `rejected`), the `on-failure` body
test that a rejected exchange logs its bodies, and the contract
tests that `rejected` is pre-registered on the events counter of
both stacks.

## Consequences

**Positive:**

- The outcome vocabulary is complete: every exchange is attributed
  to a side, and a dashboard splits the 4xx share off without a
  status query.
- WARN keeps its meaning. An alert on WARN fires for a broken peer,
  an incomplete exchange, a slow call, and for the four rejections
  that are our side's standing with the peer, not for a routine 404.
- ADR-0006's gate is the plain rule again: bodies for every outcome
  but `success`.
- Lookups, conditional requests and optimistic-locking flows log at
  INFO as before.

**Negative:**

- **Log and meter contract change:** `success` no longer includes a
  4xx. A host's dashboard or alert that keyed on `success` as "the
  peer answered" sees the 4xx share move to `rejected`; the `outcome`
  tag of `adapter.logging.events` gains the value, pre-registered at
  zero like the others (ADR-0008). Recorded in the CHANGELOG.
- A 400 or 422 that IS our defect surfaces at INFO `rejected`. An
  operator who wants to be paged for it needs a rule on the status
  field, or on `on-failure` bodies, rather than on the level.
- A 403 with a forwarded end-user token, or a 401 that a
  probe-then-refresh flow expects, logs at WARN on every occurrence.
  The set is fixed; a host with such a flow lowers the exchange
  logger's level or filters the line downstream.
- With the exchange logger set to WARN as the volume control (Common
  guide §6.5), the INFO `rejected` lines are gone together with the
  healthy ones; the four escalated statuses stay.

**Neutral:**

- The level of a 4xx outside the escalation set is what it was
  since the first release: INFO.
- A later change of the escalation set is a change of this ADR and
  of the CHANGELOG, not a property.

## History

- **2026-09-19:** first cut kept a 4xx as `success` at INFO and
  recorded the reasons against WARN. Revised the same day: the
  vocabulary gained `rejected` for the 4xx class, with WARN for the
  four statuses that are about the application's standing with the
  peer, which resolves the same reasons without a blanket WARN.
