# ADR-0014: The startup wiring report is a bounded diagnostic: DEBUG says what is in effect, TRACE where it came from

**Status:** Accepted  
**Date:** 2026-09-21  
**Deciders:** Dirk Haase (maintainer)  
**Related:** ADR-0002 (the observation line reports the decision
behind the identity contract), ADR-0003 (`ClientObservationWiring`
and `ClientLoggingPropertyOrigins` live in `legatium-common` by its
criterion), ADR-0008 (the admission rule below mirrors its blind-spot
rule for meters), ADR-0010 (the restore line reports the outcome of
its classpath detection), ADR-0005 (the masking key is redacted on
every line of the report)

## Context

Both auto-configurations write a report about themselves at context
start, on their own logger (`ClientLoggingAutoConfiguration` of each
twin, under `eu.inqudium.legatium`), never on the exchange logger:
at DEBUG whether the module is on, which properties the entry point
runs with, which customizers were registered, which builders they
attached the module to, whether Boot's client observation and
Micrometer Tracing are wired next to it and, on the reactive twin,
whether the caller's thread-locals are restored around the exchange
line; at TRACE, for every `adapter-logging.*` key some source sets,
where the value came from and which lower-precedence values it
shadows. The Common guide §6.6 documents the lines and how to read
them.

The report grew in one development cycle. All three `Added` entries
under `[Unreleased]` of the CHANGELOG are report lines, two shared
classes (`ClientObservationWiring`, `ClientLoggingPropertyOrigins`)
were added to `legatium-common` for it, the bean method of each entry
point took on `Environment` and `ObjectProvider<BoundConfiguration
Properties>` for it, and dedicated tests came with each line. The
architecture review of 2026-09-21
(`docs/assessment/ARCHITECTURE_REVIEW-2026-09-21T08-44-36.md`,
finding 3) measured about 230 production and 250 test lines for a
feature visible only at DEBUG and TRACE, noted that its rationale
stood only in the CHANGELOG, that Boot offers an actuator endpoint
(`env`) which shows a property's value per source with its origin,
and asked for the decision to be recorded before the release turns
the lines into something operators grep for - with the warning that
without a rule the next cycle adds the next line.

The forces:

**The questions are real and have no property.** Whether a client
built by the host actually carries the module, whether a call will
go out traced (which decides its request id and whether the peer
gets an `X-Correlation-Id`, ADR-0002), and whether the reactive line
will carry the caller's MDC (ADR-0010) are decided by the host's
classpath and bean set, not by any `adapter-logging.*` key. Before
the report, the only way to learn the answer was to make a call and
read the exchange line.

**The intended hosts often have no web endpoint.** The module serves
a batch job or a message consumer that calls out as much as a
servlet host (README: "Neither needs a web application"). The
actuator's `env` and `configprops` endpoints need the actuator on
the classpath, an HTTP port, and the endpoint exposed; `env` in
particular shows every property of the application and is exposed
deliberately rarely. The startup log is the one channel every host
has.

**Boot's own startup output already answers part of it.** The
condition evaluation report (DEBUG on
`org.springframework.boot.autoconfigure`) names why an
auto-configuration did or did not run. A line the module writes
about something Boot already says at startup is ceremony.

**A diagnostic that grows without a rule becomes a subsystem.** The
meter family has a rule against exactly that (ADR-0008: a meter
needs a blind spot none of the others covers); the report had none.

## Decision

**The wiring report stays, with its two stages and with an admission
rule for every line: a line answers a question about the module's
wiring that no `adapter-logging.*` key states and that Boot's own
startup log does not answer. An actuator endpoint does not count as
an answer.**

### The two stages

| Level | Question                                                | Lines                                                                                                     |
|-------|---------------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| DEBUG | **what** is in effect                                   | the enabled line, the bean line with the bound properties, the restore line (reactive twin), the customizer lines, the observation line, one attach line per builder |
| TRACE | **where** each value came from, and what it shadows     | one line per set `adapter-logging.*` key with Boot's origin, shadowed values indented beneath it; one line saying so when no key is set; one line saying so when the origins are unavailable |

DEBUG is for the operator asking "is it on and did it get my
clients"; TRACE is for the one asking "why is my value not in
effect". The stages are switched by the host's logging backend, like
the exchange logger's level (Common guide §6.5); there is no
`adapter-logging.*` key for the report, because a key would be one
more value whose origin the report would then have to explain.

### The admission rule, applied to the lines that exist

| Line                                   | Wiring question                                                            | Why Boot's startup log does not answer it                                                                 |
|----------------------------------------|----------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| enabled                                | is the auto-configuration active?                                          | the condition report lists it among hundreds of positive matches; this is the anchor of the family, and a grep for `Adapter logging` needs a first line |
| bean, with the properties              | which values does the entry point run with?                                | Boot logs no bound `@ConfigurationProperties` bean; `configprops` is an endpoint                          |
| restore (reactive twin)                | will the exchange line carry the caller's MDC? (ADR-0010)                  | a classpath detection with no property and no Boot output                                                 |
| customizer (per builder type)          | did the module register with Boot's builders?                              | conditional nested configurations; the condition report says the class matched, not that the bean attached |
| observation (once, all singletons up)  | will a call go out traced, and what does that make its request id? (ADR-0002) | decided by two optional bean sets; Boot reports neither in relation to this module                     |
| attach (per builder instance)          | does THIS client carry the module, and behind how many host interceptors?  | nothing else records which builders a customizer touched                                                  |
| origins, shadowed values (TRACE)       | where did each value come from, and which value lost?                      | Boot tracks the origin but prints it nowhere; `env` is an endpoint, and the masking key must stay redacted |

The origins line is the one the review weighed against Boot's `env`
endpoint. It stays, for the two forces above: the endpoint is absent
on the hosts the module is built for, and where it exists its
exposure is a security decision an operator does not make for a
logging library. The report renders the same facts, redacts the
masking key on every line (ADR-0005), and costs nothing unless TRACE
is on.

### The bounds

1. **Context start only.** The report describes the state of the
   context when the singletons exist. It never describes the fate of
   a call: a host can switch observation off per client, filter it
   with an `ObservationPredicate`, or build a client by hand. The
   exchange line is the per-call truth; a per-call diagnostic is not
   a report line and is out of scope here.
2. **Computed only when its level is enabled, never per call.** Each
   stage is guarded by its level check before any work; the origin
   rendering walks the property sources once.
3. **Nothing with `adapter-logging.enabled=false`.** The
   auto-configuration does not run, so no line appears, and Boot's
   condition report names the property as the reason. The module
   does not repeat that.
4. **Diagnostic, not a contract.** The facts a line carries are
   pinned by the auto-configuration tests (the level, the presence
   of each line, the redaction, the three observation renderings,
   the silence with `enabled=false`); the wording is not frozen the
   way the meter names of ADR-0008 are. A wording change is a
   `Changed` entry in the CHANGELOG, not a migration note. An
   operator's grep should key on the `Adapter logging` prefix every
   line of the family carries.
5. **One implementation for both twins where the fact is the same.**
   The observation lookup and the origin rendering live in
   `legatium-common` (ADR-0003); each twin contributes only the
   builder names and its own stack-specific line.

### Adding a line

A new line needs a row in the table above, with the wiring question
it answers and the reason Boot's startup log does not, the level per
the two stages, an entry in the Common guide §6.6, and a test that
pins its presence. A line that would answer a per-call question, or
that a `adapter-logging.*` key already states (the bean line shows
every key), does not qualify. The size of the auto-configuration is
the running cost of this decision: the bean method of each entry
point takes seven (RestClient) resp. eight (WebClient) parameters,
two of them for the report alone; a line that needs a further
collaborator injected there is a sign to stop and revisit.

**Revisit when:** Boot prints bound property origins in its own
startup output; a Boot release makes the observation or the
customizer wiring readable from the condition report; an operator
reports that a stage is never read; or a cycle adds a third
`Added` entry for the report, which is the growth this ADR exists to
bound.

## Consequences

**Positive:**

- The report has a reason and a rule in one place; the CHANGELOG
  entries that introduced it point here instead of carrying the
  rationale alone.
- An operator of a host without the actuator gets the answer to
  "is it on, did it get my clients, will the call be traced, where
  does this value come from" from the startup log, without a
  redeploy and without exposing `env`.
- The admission rule keeps the report a report: the next cycle
  cannot add a line without naming the question and Boot's silence
  on it.
- The bounds keep the per-call path untouched: nothing of the
  report runs per exchange.

**Negative:**

- The auto-configuration, the thinnest layer of the module, carries
  the widest bean signature of the project (seven resp. eight
  parameters) and about 230 production and 250 test lines for a
  feature that is off by default. This is accepted with this ADR and
  measured by the review it cites.
- The origins stage duplicates what the `env` endpoint shows on a
  host that has and exposes it. The duplication is the price of
  serving the hosts that do not.
- The lines are prose that operators will copy into runbooks; a
  wording change after the release is a CHANGELOG entry and possibly
  a broken grep on the operator's side.

**Neutral:**

- Whether the wiring report becomes the model for a future
  per-client report (a line per named client, ADR-0009) is not
  decided here; such a line would have to pass the same rule.
- The sibling project limesium carries the same report, ported from
  here in the same cycle, and records the same rule as its ADR-0008
  of the same day, with `endpoint` in place of `adapter` and its
  filter registration lines in place of the builder attach lines. A
  change of the rule is taken on both sides together, so an operator
  running the pairing reads one shape of report.
