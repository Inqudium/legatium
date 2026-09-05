# ADR-0008: Six meter families, consumed from the host's registry, never exported

- **Status:** accepted
- **Date:** 2026-09-05
- **Context:** The architecture review of 2026-09-05 (finding 1) measured the
  metrics owner at about a tenth of the production code and an eighth of the
  test code and asked what justifies a full Micrometer meter family in a
  library whose contract is "one structured log line per exchange" - noting
  that no consumer, dashboard, alert set or regulatory requirement is on
  record, and that `CONTRIBUTING.md` itself listed "metrics frameworks" as
  out of scope. The review's confidence was medium for exactly that reason:
  the meters were reasoned in the guide, but the decision to have them, and
  the rule for adding or removing one, was written down nowhere.

## Decision

**The six meter families stay, as decided here, and their names are frozen
with the first release. Every meter must close a blind spot the log line
cannot see; the registry is consumed, never exported; a host without one gets
no-ops, not a private registry.**

- **Why meters at all.** The emission architecture has failure modes that a
  log line cannot report, because the missing line IS the symptom: an
  emission that threw (`adapter.logging.failopen`), an exchange that never
  ended and therefore never emitted (`adapter.logging.exchanges.open`), a
  log pipeline that dropped events between appender and index
  (`adapter.logging.events` as the reconciliation ground truth). The
  remaining three families watch contracts the line shows only one call at a
  time: the identity propagation of ADR-0002 (`adapter.logging.correlation.id`),
  and the bytes and consumption of bodies the log field deliberately
  truncates (`adapter.*.body.size`, `adapter.response.body.read`, both
  opt-in). [Legatium guide §7.5](https://inqudium.github.io/legatium/guides/GUIDE/#75-reading-the-meters-together)
  lists the blind spot each one closes; a meter without such a row in that
  table does not belong here.
- **Why these six, and not fewer.** They mirror limesium's `endpoint.*`
  family one to one (`failopen`, `events`, `exchanges.open`,
  `correlation.id`, `request/response.body.size`, `request/response.body.read`
  with the direction swapped). An operator running the intended pairing
  reads one dashboard vocabulary for the inbound and the outbound side
  ([ADR-0007](ADR-0007-adapter-is-the-operator-vocabulary.md)); a smaller
  family on one side would break that symmetry for no measured gain.
- **Why not more.** Rates, latencies and status distributions belong to
  `http.client.requests`, which every Boot host already has; peer-level
  slowness is a log-field question (`adapter_url_host` by `adapter_outcome`).
  A new meter needs a blind spot that neither the log line, nor
  `http.client.requests`, nor an existing meter covers - and a row in §7.5.
- **Consumed, never exported.** The `MeterRegistry` is an `ObjectProvider`;
  the library defines no registry bean and adds no exporter. Micrometer
  itself stays a compile dependency of both twins: the entry points take a
  `MeterRegistry` in their public constructors, and Boot hosts carry
  `micrometer-core` with the actuator anyway. Making it optional would cost a
  no-op mirror of the owner and a conditional constructor for a saving of one
  jar in hosts without an actuator - not worth it before a consumer asks.
- **No host registry, no private registry.** The auto-configurations hand
  the entry points an empty `CompositeMeterRegistry` when the host has none:
  Micrometer's meters against it are no-ops, nothing is accumulated in a
  registry nobody can read. (The private `SimpleMeterRegistry` INSIDE the
  owner is a different thing: it is the fail-open destination for a single
  meter whose id the host registry rejected, kept per the review of
  2026-09-04, finding 7.)
- **One implementation, tested once.** The owner lives in `legatium-common`
  ([ADR-0003](ADR-0003-legatium-common-inlined-by-shade.md), amendment of
  2026-09-04); its registration behaviour - pre-registration, the
  one-owner-per-registry-and-stack rule, the fallback paths, the guarded
  updates, the cardinality folding - is tested there, in
  `ClientLoggingMetricsTest`. The twins' metrics tests keep only what their
  entry point's lifecycle decides: when the gauge moves, what the emitter
  counts, what the body tee measures.

## Consequences

- Meter names, tag keys and tag vocabularies become an external contract
  with 1.0 and follow semantic versioning: a rename or removal is a major
  change. `SharedContractTest` pins the literals.
- The cost the review measured is accepted knowingly: the owner and its
  tests are the price of six signals that are silent otherwise. Their size
  is bounded by the rule above, not by a line budget.
- The decision is re-opened by the first of: a consumer who needs
  `micrometer-core` off the classpath, a Micrometer major that changes the
  registration semantics the fallback paths rely on, or an operator report
  that a meter is never read - in which case it is removed in the next
  major, not silently.
