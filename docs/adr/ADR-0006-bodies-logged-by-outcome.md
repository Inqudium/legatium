# ADR-0006: Body logging is a mode per direction, gated by the outcome

- **Status:** accepted
- **Date:** 2026-09-03
- **Context:** `log-request-body` / `log-response-body` were booleans:
  `true` meant every body of every call. That is the switch that decides
  the log VOLUME, and it had only two positions - off, or everything. What
  is nearly always wanted in practice is "bodies only when something went
  wrong": that reduces the volume by orders of magnitude and hits exactly
  the calls a body is wanted for. The structure for it already existed -
  the outcome is final before the line is written - only the third position
  was missing. The catch is the request body: it flows before the outcome
  is known, so it has to be captured and, in the common case, thrown away.
  That costs the capture but saves the output, and the output is what
  burdens the log pipeline (ELK, the shippers, the indices).

## Decision

**Each body direction has a mode, `never` | `on-failure` | `always`, not a
switch:**

1. `never` (the default) captures nothing for logging. A size meter may
   still install a count-only capture, exactly as before.
2. `on-failure` captures the body on every call - bounded by
   `max-body-bytes`, like `always` - and writes it to the line only when
   `client_outcome` is not `success`: `failure`, `timeout`, and on the
   WebClient twin `cancelled`, or when the status is a 4xx. The response side decides at emission, when
   the outcome is final; the request side captures ahead and discards.
3. `always` captures and logs on every call - the former `true`.
4. The gate is wider than the outcome vocabulary by exactly one status
   class: a 4xx answer keeps its `success` outcome (the peer answered; the request was wrong) - levels, metrics and dashboards are
   untouched - but its bodies are logged, because a client's error is
   exactly the case a body explains. A 5xx is a `failure` and logs as
   well. A slow but healthy call stays `success` and logs no bodies.
   *Amended the same day:* the first cut followed the vocabulary strictly
   and withheld 4xx bodies; that hid validation errors, the bodies most
   often wanted.
5. The former booleans are refused at binding time (`true` is not a mode
   name): an operator who believed body logging on must see the migration
   at startup, not discover a silent `never` in production.

The mode lives in the shared core (`BodyLogMode`), so both twins and the
sibling project limesium, whose namespace mirrors this one, gate the same
way.

## Consequences

- Body logging becomes affordable outside a debug session:
  `on-failure` logs the bodies that explain an incident and nothing else.
- `on-failure` costs the request-body capture on every call (memory up to
  `max-body-bytes` per in-flight exchange, and on the reactive twin the
  tee's transient copy per buffer), whether or not the line ends up with a
  body. `measure-*-body-size` is unchanged: it still measures what flowed,
  in every mode.
- Source and configuration change: `true` / `false` become `always` /
  `never`; the properties' type changes from `Boolean` to `BodyLogMode`.
  The reference configuration, the lockstep tests and both twins' guides
  carry the new vocabulary.
