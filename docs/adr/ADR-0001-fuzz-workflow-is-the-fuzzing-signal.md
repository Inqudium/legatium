# ADR-0001: The Fuzz workflow, not the Scorecard score, is the fuzzing signal

- **Status:** accepted
- **Date:** 2026-09-03 (adopted from the sibling project limesium, whose
  ADR-0001 of 2026-08-30 established both the decision and the ADR series
  the Inqudium projects share)
- **Context:** The OpenSSF Scorecard **Fuzzing** check scans for Jazzer
  targets only when Java holds a "prominent" share of a repository's
  bytes - at least (total ÷ languages) ÷ 4 per GitHub's linguist
  statistics, i.e. 12.5 % with the two detected languages Kotlin and
  Java. This Kotlin-dominated codebase keeps its Jazzer `@FuzzTest`
  classes in Java precisely so that the detector *could* see them
  (`com.code_intelligence.jazzer.api.FuzzedDataProvider` is grepped in
  `*.java` files), yet their byte share sits far below the gate, so the
  score reads 0 - or flips between 0 and 10 with ordinary commits - while
  the nightly Fuzz workflow runs the targets green. Verified against the
  Scorecard v5.5.0 source (`checks/raw/fuzzing.go`) in limesium.

## Decision

**The Fuzz workflow's run history is the authoritative fuzzing signal;
the Scorecard Fuzzing score is accepted as 0 (or flapping) and is not
acted on.**

Rejected alternatives:

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

The reader-facing consequence lives in `SECURITY.md` (Scorecard scope
note): the badge's Fuzzing line tracks the language ratio, not the
fuzzing coverage.

## Consequences

- The Fuzzing score may flip back to 10 (or to 0 again) without any
  change in fuzzing coverage; neither direction warrants action, and
  "Fuzzing is 0 again" is answered by this ADR.
- The overall Scorecard score carries a standing deduction of medium
  weight; this is accepted alongside the other single-maintainer
  deductions already documented in `SECURITY.md`.
- Revisit if Scorecard drops the prominent-language gate for fuzz
  detection, adds jazzer-junit `@FuzzTest` or Kotlin detection, or if
  the project ever joins OSS-Fuzz (detected independently of language).
