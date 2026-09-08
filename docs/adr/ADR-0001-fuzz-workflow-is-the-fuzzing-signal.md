# ADR-0001: The Fuzz workflow, not the Scorecard score, is the fuzzing signal

**Status:** Accepted  
**Date:** 2026-09-03  
**Deciders:** Dirk Haase (maintainer)

## Context

The OpenSSF Scorecard **Fuzzing** check scans for Jazzer targets only
when Java holds a "prominent" share of a repository's bytes: at least
(total ÷ languages) ÷ 4 per GitHub's linguist statistics, i.e. 12.5 %
with the two detected languages Kotlin and Java. Scorecard does
support Jazzer (it greps `*.java` files for
`com.code_intelligence.jazzer.api.FuzzedDataProvider`), and this
Kotlin-dominated codebase keeps its Jazzer `@FuzzTest` classes in
Java precisely so that the detector *could* see them. Yet their byte
share sits far below the gate, so the score reads 0, or flips between
0 and 10 with ordinary commits, while the nightly Fuzz workflow runs
the targets green. The cause was verified against the Scorecard
v5.5.0 source (`checks/raw/fuzzing.go`) in the sibling project
limesium.

This decision was adopted from limesium, whose ADR-0001 of 2026-08-30
established both the decision and the ADR series the Inqudium projects
share. This ADR likewise establishes the series for legatium as a
stable reference target (`ADR-NNNN`), following the convention of
tabellarium. The record format is described in
[ADR-FORMAT.md](ADR-FORMAT.md); the number is never reused or changed
because it is cited from code comments, the CHANGELOG and review
reports.

## Decision

**The Fuzz workflow's run history is the authoritative fuzzing signal.
The Scorecard Fuzzing score is accepted as 0 (or flapping) and is not
acted on.**

The reader-facing consequence lives in `SECURITY.md` (Scorecard scope
note): the badge's Fuzzing line tracks the language ratio, not the
fuzzing coverage.

**Revisit when:** Scorecard drops the prominent-language gate for fuzz
detection, adds jazzer-junit `@FuzzTest` or Kotlin detection, or the
project joins OSS-Fuzz (which is detected independently of language).

## Consequences

**Positive:**

- The fuzzing evidence stays where it is produced: the Fuzz workflow's
  run history, with the targets and their invariants in the test
  bodies.
- "Fuzzing is 0 again" is answered by this ADR; neither direction of a
  flip warrants action.

**Negative:**

- The overall Scorecard score carries a standing deduction of medium
  weight; this is accepted alongside the other single-maintainer
  deductions already documented in `SECURITY.md`.
- The Fuzzing score may flip back to 10 (or to 0 again) without any
  change in fuzzing coverage, so the badge misrepresents the project in
  both directions.

**Neutral:**

- The fuzz tests remain Java classes under `src/test/java`, which is
  what jazzer-junit requires; the language ratio is a by-product of the
  codebase, not a lever this project pulls.

## Considered and rejected

- **Introducing ClusterFuzzLite** to satisfy the detector (it is
  detected by file presence, `.clusterfuzzlite/Dockerfile`). The
  Inqudium projects deliberately avoid it: its OSS-Fuzz base images are
  pinned to JDK 17, while this project builds on a newer JDK.
- **Gaming the linguist statistics** (`.gitattributes` overrides, or
  inflating the Java share) so that Java crosses the 12.5 % line. The
  language statistics would then misrepresent the codebase to fix a
  number that misrepresents the fuzzing.
- **Converting the fuzz tests to Kotlin** would not help either way:
  Scorecard has no Kotlin fuzzer spec at all.
