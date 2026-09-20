# ADR-0008: Six meter families, consumed from the host's registry, never exported

**Status:** Accepted  
**Date:** 2026-09-05  
**Last updated:** 2026-09-20  
**Deciders:** Dirk Haase (maintainer)  
**Related:** ADR-0002 (`adapter.logging.correlation.id` watches its
identity contract), ADR-0003 (the owner `ClientLoggingMetrics` lives
in `legatium-common` since 2026-09-04 and is tested once there),
ADR-0006 (the body meters measure what flowed in every body mode),
ADR-0007 (the `adapter.*` prefix the meter names carry)

## Context

The architecture review of 2026-09-05
(`docs/assessment/ARCHITECTURE_REVIEW-2026-09-05T00-24-58.md`,
finding 1) measured the metrics owner at about a tenth of the
production code and an eighth of the test code and asked what
justifies a full Micrometer meter family in a library whose contract
is "one structured log line per exchange". It noted that no consumer,
dashboard, alert set or regulatory requirement is on record, and that
`CONTRIBUTING.md` itself listed "metrics frameworks" as out of scope.

The review's confidence was medium for exactly that reason: the
meters were reasoned in the guide, but the decision to have them, and
the rule for adding or removing one, was written down nowhere.

## Decision

**The six meter families stay, and their names are frozen with the
first release. Every meter must close a blind spot the log line
cannot see; the registry is consumed, never exported; a host without
one gets no-ops, not a private registry.**

### Why meters at all

The emission architecture has failure modes that a log line cannot
report, because the missing line IS the symptom: an emission that
threw (`adapter.logging.failopen`), an exchange that never ended and
therefore never emitted (`adapter.logging.exchanges.open`), a log
pipeline that dropped events between appender and index
(`adapter.logging.events` as the reconciliation ground truth). The
remaining three families watch contracts the line shows only one call
at a time: the identity propagation of ADR-0002
(`adapter.logging.correlation.id`), and the bytes and consumption of
bodies the log field deliberately truncates (`adapter.*.body.size`,
`adapter.response.body.read`, both opt-in).

| Family                             | Blind spot it closes                                              |
|------------------------------------|-------------------------------------------------------------------|
| `adapter.logging.failopen`         | an emission that threw and therefore wrote no line                |
| `adapter.logging.exchanges.open`   | an exchange that never ended and therefore never emitted          |
| `adapter.logging.events`           | events dropped between appender and index (reconciliation)        |
| `adapter.logging.correlation.id`   | where each call's request id came from, across calls (ADR-0002)   |
| `adapter.*.body.size` (opt-in)     | the bytes that flowed, which the log field truncates              |
| `adapter.response.body.read` (opt-in) | whether the response body was consumed at all                  |

The [Common guide §7.5](https://inqudium.github.io/legatium/guides/GUIDE/#75-reading-the-meters-together)
carries the operator-facing version of this table; a meter without
such a row does not belong here.

### Why these six, and not fewer

They mirror limesium's `endpoint.*` family one to one (`failopen`,
`events`, `exchanges.open`, `correlation.id`,
`request/response.body.size`, `request/response.body.read` with the
direction swapped). An operator
running the intended pairing reads one dashboard vocabulary for the
inbound and the outbound side (ADR-0007); a smaller family on one
side would break that symmetry for no measured gain.

### Why not more

Rates, latencies and status distributions belong to
`http.client.requests`, which every Boot host already has; peer-level
slowness is a log-field question (`adapter_url_host` by
`adapter_outcome`). A new meter needs a blind spot that neither the
log line, nor `http.client.requests`, nor an existing meter covers,
and a row in the table above and in §7.5.

### Consumed, never exported

The `MeterRegistry` is an `ObjectProvider`; the library defines no
registry bean and adds no exporter. Micrometer itself stays a compile
dependency of both twins: the entry points take a `MeterRegistry` in
their public constructors, and Boot hosts carry `micrometer-core`
with the actuator anyway. Making it optional would cost a no-op
mirror of the owner and a conditional constructor for a saving of one
jar in hosts without an actuator, not worth it before a consumer
asks.

### No host registry, no private registry

The auto-configurations hand the entry points an empty
`CompositeMeterRegistry` when the host has none: Micrometer's meters
against it are no-ops, nothing is accumulated in a registry nobody
can read. The private `SimpleMeterRegistry` INSIDE the owner is a
different thing: it is the fail-open destination for a single meter
whose id the host registry rejected, kept per finding 7 of
`docs/assessment/ARCHITECTURE_REVIEW-2026-09-04T21-49-30.md`.

### One implementation, tested once

The owner lives in `legatium-common` (ADR-0003, move of 2026-09-04);
its registration behaviour (pre-registration, the
one-owner-per-registry-and-stack rule, the fallback paths, the
guarded updates, the cardinality folding) is tested there, in
`ClientLoggingMetricsTest`. The twins' metrics tests keep only what
their entry point's lifecycle decides: when the gauge moves, what the
emitter counts, what the body tee measures.

**Revisit when:** a consumer needs `micrometer-core` off the
classpath, a Micrometer major changes the registration semantics the
fallback paths rely on, or an operator reports that a meter is never
read. In the last case it is removed in the next major, not silently.

## Consequences

**Positive:**

- Six signals that are silent otherwise have a stated reason each,
  and the rule for the seventh is written down.
- One dashboard vocabulary for the inbound and the outbound side of
  the intended limesium pairing.
- A host without an actuator pays nothing beyond the jar: no-op
  meters, no private accumulation.

**Negative:**

- Meter names, tag keys and tag vocabularies become an external
  contract with 1.0 and follow semantic versioning: a rename or
  removal is a major change. `SharedContractTest` pins the literals.
- The cost the review measured is accepted knowingly: the owner and
  its tests are the price of the six signals. Their size is bounded by
  the rule above, not by a line budget.
- `micrometer-core` stays a compile dependency of both twins even for
  hosts that never read a meter.

**Neutral:**

- The body meters are opt-in and measure what flowed in every body
  mode (ADR-0006); switching body logging off does not switch them
  off.

## History

- **2026-09-05:** decided; the six families and the seventh name
  as the review of that day left them.
- **2026-09-19:** the dynamic body meters (the two size summaries
  and the read-state counter) are resolved once per tag set and kept
  in a cache that mirrors the registry's entries - a meter the
  registry did not keep, or kept under folded tag values, is never
  cached, and a removal listener drops an entry the host removes.
  The cache is never written from inside the registry's lock
  (Micrometer notifies removal listeners under its meter-map lock
  while a registration takes that same lock); a miss resolves the
  meter outside the map and publishes it with `putIfAbsent`.
  Accepted residue of that order: between the registration returning
  and the `putIfAbsent`, a host removal of that very meter can leave a
  detached instance in the cache, which then takes every later sample
  of its tag set until the owner is recreated - it costs the samples
  of one tag set, never an event or a call, and closing it would take
  a registry-wide lookup after every first-time registration.
  `ClientLoggingMetrics` carries the rule; this entry is its reference.
- **2026-09-20:** this history recorded, so the code names the ADR
  instead of the analysis report that raised the residue (comment
  audit of 2026-09-20, finding 51).
