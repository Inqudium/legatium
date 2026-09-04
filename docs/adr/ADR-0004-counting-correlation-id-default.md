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
probabilistic with 64 bits of prefix entropy. The rationale follows here;
the class carries only the constraints the code cannot express.

### Why not a random UUID per call

`UUID.randomUUID().toString()` draws 16 bytes from a process-wide,
statically shared `SecureRandom` on every call. On a reactive stack that
is the wrong shape twice over: the reseeding path of the native provider
reads a system entropy source behind a monitor, which is blocking work on
an event loop and a pinning point under virtual threads. The counting
generator draws randomness exactly once, at construction time, and the
per-call path is a single atomic increment plus a radix conversion. The
latency difference is unlikely to be visible in a logging pipeline; the
structural argument - no shared lock, no I/O in the hot path - is what
motivates the choice.

### Uniqueness model

Within one instance, uniqueness is guaranteed rather than probable: the
counter never repeats. Across instances it is probabilistic, and a prefix
collision is worse than a UUID collision: two colliding instances do not
produce one duplicate id, they produce two near-identical id sequences,
because both counters start at zero. This is why the prefix is not
narrowed below 64 bits - entropy in the prefix is what bounds that failure
mode. With 64 bits and 10,000 instance starts inside a log retention
window the birthday probability is around 3e-12. A colliding prefix is not
silently unrecoverable: log entries carry the pod name as platform
metadata, so the two sequences remain separable by an instance filter.

### Entropy source of the prefix

The prefix is seeded from `SecureRandom` rather than `ThreadLocalRandom`,
and the reason is entropy, not security. `ThreadLocalRandom` derives its
process-wide initial seed from `currentTimeMillis` and `nanoTime` unless
`-Djava.util.secureRandomSeed=true` is set; for pods started seconds apart
during a rolling update the wall clock contributes almost nothing, and
`nanoTime` shares an origin across containers on the same node, leaving
little more than JVM startup jitter - which would invalidate the birthday
estimate by orders of magnitude. The usual objection to `SecureRandom`
(blocking, lock contention) applies to the per-call path only; this runs
once, at construction.

### Ordering and widths

Base 36 uses `[0-9a-z]`, whose ASCII code points are ordered consistently
with their digit values, so for equal-length strings lexicographic order
equals numeric order; combined with the fixed widths, ids from one
instance sort in the order the counter handed them out - the order of id
*allocation*, not of log *emission*, so the id is a tiebreaker, not a
primary sort key. Callers must not upper-case the value (`A-Z` sits between
the digits and `a-z` in ASCII). The widths are load-bearing twice: they
make the unseparated concatenation unambiguous (the split point is always
at 13), and they keep the ordering. 36^8 is about 2.8e12 ids - roughly nine
years at a sustained 10,000 ids per second, longer than any instance
lives; a value exceeding its width would grow by a character and silently
break both properties, so there is no runtime overflow check (unreachable
at this width, and a branch in the hot path) but an executable guard: the
width-boundary test drives the counter to the last in-width value through
a test seam.

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
