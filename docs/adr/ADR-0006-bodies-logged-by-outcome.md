# ADR-0006: Body logging is a mode per direction, gated by the outcome

**Status:** Accepted  
**Date:** 2026-09-03  
**Last updated:** 2026-09-05  
**Deciders:** Dirk Haase (maintainer)  
**Related:** ADR-0003 (`BodyLogMode` lives in `legatium-common` by
its criterion), ADR-0005 (the companion configuration decision of the
same day), ADR-0008 (the body meters measure what flowed in every
mode)

## Context

`log-request-body` / `log-response-body` were booleans: `true` meant
every body of every call. That is the switch that decides the log
VOLUME, and it had only two positions: off, or everything.

What is nearly always wanted in practice is "bodies only when
something went wrong": that reduces the volume by orders of magnitude
and hits exactly the calls a body is wanted for. The structure for it
already existed (the outcome is final before the line is written);
only the third position was missing.

The catch is the request body: it flows before the outcome is known,
so it has to be captured and, in the common case, thrown away. That
costs the capture but saves the output, and the output is what
burdens the log pipeline (ELK, the shippers, the indices).

## Decision

**Each body direction has a mode, `never` | `on-failure` | `always`,
not a switch.**

| Mode         | Captures                                                                              | Writes the body to the line                |
|--------------|---------------------------------------------------------------------------------------|--------------------------------------------|
| `never`      | nothing for logging (a size meter may still install a count-only capture, as before)  | never                                      |
| `on-failure` | on every call, bounded by `max-body-bytes`                                            | only when the call failed (gate below)     |
| `always`     | on every call, bounded by `max-body-bytes`                                            | always (the former `true`)                 |

`never` is the default.

**The gate.** `on-failure` writes the bodies when `adapter_outcome` is
not `success` (`failure`, `timeout`, and on the WebClient twin
`cancelled`), or when the status is a 4xx. The response side decides
at emission, when the outcome is final; the request side captures
ahead and discards. The gate is wider than the outcome vocabulary by
exactly one status class: a 4xx answer keeps its `success` outcome
(the peer answered; the request was wrong), so levels, metrics and
dashboards are untouched, but its bodies are logged, because a
client's error is exactly the case a body explains. A 5xx is a
`failure` and logs as well. A slow but healthy call stays `success`
and logs no bodies.

**The gate follows the exchange's outcome, not the caller's.** A
`200` whose body the application's Jackson converter or decoder
cannot map to the requested type is a failure for the caller and a
`success` for the exchange: every byte flowed, the status is final,
and the decoding happens above the interceptor resp. downstream of
the body tee, where neither client offers a seam that could inform
the module. `on-failure` therefore withholds the bodies of exactly
the answer an operator would want to see. This is accepted as the
boundary of the design (the module observes the wire) and documented
in the Common guide (§6.3): `always` is the way to capture such a
peer's answers during an analysis. Both twins pin the behaviour with
a test, so a future change is a decision.

**Binding.** The former booleans are refused at binding time (`true`
is not a mode name): an operator who believed body logging on must
see the migration at startup, not discover a silent `never` in
production.

**Scope:** the mode lives in the shared core (`BodyLogMode`), so both
twins and the sibling project limesium, whose namespace mirrors this
one, gate the same way.

## Consequences

**Positive:**

- Body logging becomes affordable outside a debug session:
  `on-failure` logs the bodies that explain an incident and nothing
  else.
- A misconfigured `true` fails loudly at startup instead of silently
  logging nothing.

**Negative:**

- `on-failure` costs the request-body capture on every call (memory
  up to `max-body-bytes` per in-flight exchange, and on the reactive
  twin the tee's transient copy per buffer), whether or not the line
  ends up with a body.
- Decoding failures above the module are invisible to the gate; an
  analysis of such a peer needs `always`.
- **Source and configuration change:** `true` / `false` become
  `always` / `never`; the properties' type changes from `Boolean` to
  `BodyLogMode`. The reference configuration, the lockstep tests and
  the guides carry the new vocabulary. Landed before the first
  release.

**Neutral:**

- `measure-*-body-size` is unchanged: it still measures what flowed,
  in every mode.
- A 4xx is logged like a failure for bodies only; its outcome, level
  and metrics stay those of a `success`.

## History

- **2026-09-03:** modes introduced; the first cut followed the outcome
  vocabulary strictly and withheld 4xx bodies.
- **2026-09-03 (same day):** the gate widened to 4xx answers. The
  strict version hid validation errors, the bodies most often wanted.
- **2026-09-05:** the decoding boundary (a `200` the application
  cannot map) was documented and pinned by a test in both twins as
  the accepted limit of the gate.
