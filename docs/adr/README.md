# Architecture Decision Records

This directory holds the Architecture Decision Records for
**legatium**. Every record follows the shape in
[ADR-FORMAT.md](ADR-FORMAT.md). ADRs are numbered
`ADR-NNNN-short-kebab-slug.md`; the number is never reused or changed,
because it is cited from code comments, the POMs, the CHANGELOG, and
review reports. The **code is authoritative**: where an ADR and the
code disagree, the code wins and the ADR is corrected.

New to the project? Start with
[ADR-0003](ADR-0003-legatium-common-inlined-by-shade.md), which
explains why there are two twin modules plus an unpublished common
module and where a given piece of code lives, then read
[ADR-0002](ADR-0002-trace-id-is-the-request-id.md) for the identity
each exchange line carries, and finally the cluster you are touching.

## Index by topic

### Exchange identity & wire contract

| ADR | Title | Status |
|-----|-------|--------|
| [0002](ADR-0002-trace-id-is-the-request-id.md) | The trace id is the request id; the correlation header is sent only on traceless calls | Accepted; last updated 2026-09-04 |
| [0004](ADR-0004-counting-correlation-id-default.md) | The default correlation id is a counting id, not a UUID | Accepted |
| [0009](ADR-0009-adapter-name-is-a-request-attribute.md) | A client's name is a request attribute, logged as `adapter_name` | Accepted |
| [0010](ADR-0010-reactive-twin-restores-the-callers-context.md) | The reactive twin restores the caller's context from the Reactor Context around its emission | Accepted; last updated 2026-09-16 |
| [0011](ADR-0011-blocking-twin-snapshots-the-callers-mdc.md) | The blocking twin snapshots the caller's MDC for a response closed on another thread | Accepted |

### Module structure & build

| ADR | Title | Status |
|-----|-------|--------|
| [0003](ADR-0003-legatium-common-inlined-by-shade.md) | Byte-identical twin code lives in legatium-common, inlined by Shade | Accepted; last updated 2026-09-05 |

### Configuration & logged content

| ADR | Title | Status |
|-----|-------|--------|
| [0005](ADR-0005-headers-masked-by-default.md) | Logged header values are masked by default; plaintext is an explicit allowlist | Accepted |
| [0006](ADR-0006-bodies-logged-by-outcome.md) | Body logging is a mode per direction, gated by the outcome | Accepted; last updated 2026-09-05 |

### Operator surface & observability

| ADR | Title | Status |
|-----|-------|--------|
| [0007](ADR-0007-adapter-is-the-operator-vocabulary.md) | The operator-facing vocabulary is `adapter`, the counterpart of `endpoint` | Accepted; last updated 2026-09-05 |
| [0008](ADR-0008-six-meters-consumed-not-exported.md) | Six meter families, consumed from the host's registry, never exported | Accepted |

### Conventions & project process

| ADR | Title | Status |
|-----|-------|--------|
| [0001](ADR-0001-fuzz-workflow-is-the-fuzzing-signal.md) | The Fuzz workflow, not the Scorecard score, is the fuzzing signal | Accepted; also establishes the ADR series as reference target |

## Superseded

None yet. A fully superseded record keeps its number, gets the status
`Superseded by ADR-NNNN (YYYY-MM-DD)`, and moves to this table; it
must not be treated as current guidance.

## Notes

- **Format & conventions:** [ADR-FORMAT.md](ADR-FORMAT.md).
- **Evidence:** the review reports that ADRs cite live in
  [`../assessment/`](../assessment/); they are dated snapshots and
  are not updated when an ADR changes.
- **Siblings:** legatium is the outbound twin of limesium; several
  decisions were adopted from there or taken here first and mirrored
  there, and all follow the convention of tabellarium. The ADR says
  so where it applies, but each project keeps its own record.
