# ADR-0004: The default correlation id is a counting id, not a UUID

- **Status:** accepted
- **Date:** 2026-09-03 (adopts the decision of limesium's ADR-0004 of
  2026-08-30 for the outbound side, with the identical implementation
  shared through legatium-common)
- **Context:** The obvious default for a generated correlation id is
  `UUID.randomUUID()`, which draws 16 bytes from the process-wide,
  statically shared `SecureRandom` on every traceless call. That is the
  wrong shape for this library's hot path twice over: the native
  provider's reseeding reads a system entropy source behind a monitor -
  blocking work on a reactive event loop (the WebClient twin) and a
  pinning point under virtual threads (the RestClient twin on a
  virtual-thread executor). The latency is unlikely to be visible in a
  logging pipeline; the structural argument (no shared lock, no I/O per
  call) is what decides.

## Decision

**`CorrelationIdGenerator.DEFAULT` is a `CountingCorrelationIdGenerator`:
a random per-instance base-36 prefix (13 chars, seeded once from
`SecureRandom` at construction) followed by a monotonically increasing
counter (8 chars) - 21 lowercase alphanumeric characters, fixed width,
lexicographically ordered per instance.** Uniqueness within an instance
is guaranteed (the counter never repeats); across instances it is
probabilistic with 64 bits of prefix entropy. The full rationale
(entropy source, widths, ordering, failure modes) lives on the class.

## Consequences

- Peers that parse or validate the correlation header they receive see
  21-char base-36 ids, not 36-char UUIDs. A host that must send UUIDs
  (a peer's contract, compliance tooling) overrides the
  `CorrelationIdGenerator` bean - the extension point exists for exactly
  that.
- Ids from one instance sort in allocation order - usable as a
  tiebreaker for same-timestamp log entries; not a global sort key.
- `DEFAULT` is a JVM-global singleton: every context in a JVM shares one
  prefix and one counter, which preserves uniqueness (a fresh context
  does not restart the sequence). A host running limesium AND legatium
  holds two such singletons (different classes, different prefixes) -
  inbound and outbound ids never collide by construction.
- The generator is consulted only for traceless calls without a
  correlation header (ADR-0002); in a host with tracing configured it is
  never consulted at all.
