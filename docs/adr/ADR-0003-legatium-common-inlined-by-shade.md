# ADR-0003: Byte-identical twin code lives in legatium-common, inlined by Shade

**Status:** Accepted  
**Date:** 2026-09-03  
**Last updated:** 2026-09-05  
**Deciders:** Dirk Haase (maintainer)  
**Related:** ADR-0002 (the `Traceparent` parser and `MdcScope` it
relies on are shared by this ADR's criterion), ADR-0004
(`CorrelationIdGenerator` is a resident of the common module),
ADR-0005 (`HeaderLogProperties` and `HeaderValueMasker` likewise),
ADR-0006 (`BodyLogMode` likewise), ADR-0008 (`ClientLoggingMetrics`
moved here on 2026-09-04 and is tested once)

## Context

The sibling project limesium started with two deliberately duplicated
twins, then learned (its ADR-0003 of 2026-08-30, with two amendments)
that every shared-layer change became a synchronized multi-file port
in both directions, and extracted the byte-identical set into an
internal module inlined by Shade, under the constraint that consumers
keep adding exactly ONE artifact to their build.

Legatium is built as limesium's twin project and inherits that lesson
from day one instead of re-learning it. Unlike limesium, an
application carrying BOTH twins (a servlet host that also uses
`WebClient`) is a supported deployment here, which shapes the
relocation and module-path consequences below.

## Decision

**The byte-identical shared code lives in the `legatium-common`
module; each twin inlines it into its own jar with the Maven Shade
plugin; `legatium-common` itself is never published.**

### The criterion

Code whose twin copies are byte-identical moves to `legatium-common`
(package `eu.inqudium.legatium.common`). Since 2026-09-04 the line is
a measured rule rather than a review: a twin-paired file that reaches
**90 % line similarity** after neutralising the stack names is
byte-identical enough to move, parameterised where it must differ.
Below that, a copy stays a copy and the both-directions port is the
accepted cost. Each review that finds a file above the line moves it;
every move is recorded in [History](#history).

### What lives in `legatium-common`

| Resident                                                                                                       | Since      | Route                                                                   |
|----------------------------------------------------------------------------------------------------------------|------------|-------------------------------------------------------------------------|
| `Traceparent` (with unit test, conformance fixture and Jazzer fuzz target)                                     | 2026-09-03 | original extraction                                                     |
| `NanoTimeSource`, `CorrelationIdGenerator` (with the counting default, ADR-0004), `reportQuietly`/`failOpen`   | 2026-09-03 | original extraction                                                     |
| `Mdc.kt` (`MdcKeys`/`TraceMdcKeys`/`MdcScope`)                                                                 | 2026-09-03 | original extraction                                                     |
| `HeaderLogProperties` (selection semantics, with unit test and fuzz target), `HeaderValueMasker`               | 2026-09-03 | original extraction; see ADR-0005                                       |
| `BodyReadState` (`decodeTruncated` moved beneath `BoundedByteBuffer` on 2026-09-17), `Timeouts` (one `adapter_outcome=timeout` on both stacks) | 2026-09-03 | original extraction                                                     |
| `ClientLogField` with its builder extensions; `ClientLogFieldTest` binds the ELK component template here       | 2026-09-03 | first amendment                                                         |
| `ClientLoggingProperties` (the `adapter-logging.*` binding) with its unit and reference-config tests            | 2026-09-03 | second amendment                                                        |
| `CorrelationHeader` (the acceptance rule of ADR-0002)                                                          | 2026-09-04 | `CODE_ANALYSIS-2026-09-04T20-56-15.md`, finding 13                      |
| `ClientLoggingMetrics` parameterised by `ClientStack`; `ClientActivation`                                      | 2026-09-04 | `ARCHITECTURE_REVIEW-2026-09-04T21-49-30.md`, finding 1                 |
| `SharedContractTest` (the shared literals, pinned once)                                                        | 2026-09-05 | `ARCHITECTURE_REVIEW-2026-09-05T00-24-58.md`, finding 3                 |
| `BoundedByteBuffer` (the byte-bounded buffer beneath both `BoundedBodyCapture`s, with unit test and fuzz target) | 2026-09-17 | extraction with the mark/reset and buffer-sizing work                   |

Later residents that arrive with ordinary changes follow the same
criterion; the module's source tree is the authoritative list.

### What deliberately stays duplicated

Everything whose twin copies differ in design, not in data:

- the emitters (72 % similar on 2026-09-04), the exchanges, the
  interceptor and the filter;
- `BoundedBodyCapture`: two different concurrency designs, volatile
  single-writer on the blocking stack, lock and freeze on the reactive
  one - the shells; the bounded buffer beneath them (the bytes, the
  cap, the truncated rendering) is one `BoundedByteBuffer` in
  `legatium-common` since 2026-09-17.

For those the accepted cost is a conscious port in both directions.
Each twin's `TwinContractTest` pins the two facts the twin owns (its
`ClientStack` and the message text its emitter renders); the shared
literals are pinned once in `legatium-common`. These tests catch
*named* contract drift, not behavioural drift.

### Shading

An `artifactSet` restricted to `eu.inqudium:legatium-common`, NO
relocation (relocating rewrites bytecode but not Kotlin metadata, and
would break the shared bean contract; see Consequences),
`keepDependenciesWithProvidedScope=false` so the dependency-reduced
POM drops the dependency entirely, and the module's `META-INF/maven`
filtered out of the shaded jar. spring-boot-starter-parent
pre-configures an unnamed uber-jar shade execution; it is unbound
(`phase=none`) so declaring the plugin does not swallow the compile
classpath.

### Visibility

The twins compile with `-Xfriend-paths` (own output dir, common's
classes dir AND jar; the reactor resolves the dependency as a
directory before packaging and as a jar afterwards), so the shared
classes stay `internal`. A twin therefore builds only through the
reactor (from the root, or with `-am`): a lone `-pl <twin>` build
resolves the dependency from the local repository, which is not a
friend path, and fails with "internal in file".

### Not published

`maven.deploy.skip=true` plus `skipPublishing=true` for the Central
Portal bundle. The published twin POMs mention no `legatium-common`.
`legatium-common` depends on `spring-boot` for the
`@ConfigurationProperties` annotation (no autoconfigure, no starter)
and on `micrometer-core` for the metrics owner; both twins declared
those already.

### Verification on the consumer's side

The standalone project `consumer-smoke/` (no reactor child, like
limesium's `benchmarks/`) depends on both twins exactly as an
application does and starts a Boot context on the installed jars: the
inlined common classes must resolve from exactly the two twin jars and
from no `legatium-common` artifact, both auto-configurations must wire
up through the jars' own imports files, and one call per client must
end in one exchange line against a real local peer. The CI job
`consumer-smoke` installs the reactor, DELETES `legatium-common` from
the local repository and only then builds the consumer: a
dependency-reduced POM that still named the unpublished module fails
there, not at the first consumer.

### Documentation

Each twin's Dokka run includes the common sources as an additional
source root: the API reference documents what the shaded jar actually
contains, and cross-module KDoc links resolve under `failOnWarning`.
The Docs workflow installs (not merely verifies) before the per-module
Dokka runs, so the dependency resolves.

**The inlining stays through 1.0** (finding 4 of
`docs/assessment/ARCHITECTURE_REVIEW-2026-09-04T21-49-30.md`, decided).
Publishing `legatium-common` as a regular artifact would remove the
friend-path build coupling, the duplicate classes and the JPMS
split-package exclusion, at the price of one more artifact in every
consumer's tree and a public API surface for the shared types. The
one-artifact shape and `internal` shared types are kept for the first
release.

**Revisit when:** a consumer needs the module path with both twins, a
third twin appears, or a shared-type API change would break consumers
of both jars.

## Consequences

**Positive:**

- A shared-layer change is made ONCE; the both-directions port and its
  drift risk exist only for the deliberately duplicated remainder.
- Consumers are unaffected in shape: one artifact, no new transitive
  dependency, internals stay internal.
- A host bean of `HeaderValueMasker`, `NanoTimeSource` or
  `CorrelationIdGenerator` masks or clocks BOTH twins, and a host that
  imports `ClientLoggingProperties` for a custom interceptor or filter
  bean writes one import; a host carrying both twins gets one
  properties bean (Boot derives the bean name from prefix and class).
  This is what NOT relocating buys.
- The common classes carry the `eu.inqudium.legatium.common` package,
  distinct from limesium's `eu.inqudium.limesium.common`, so a host
  that runs limesium AND legatium (the intended pairing) never sees two
  copies of one class name.
- The both-twins-on-one-classpath case is exercised on every push by
  the consumer-smoke job.

**Negative:**

- **Both twins in one application carry the same classes twice**,
  under the same names, and the first jar on the classpath wins. That
  is safe only while the two versions are byte-identical, hence "keep
  the two versions equal" in both READMEs; a version skew between the
  twins is a misconfiguration.
- **The JPMS module path is unsupported** for a host carrying both
  twins: two automatic modules exporting the same package are a
  split-package error and the application does not start. A single
  twin on the module path is fine. (The build itself runs Surefire
  with `useModulePath=false` for the same reason.)
- `-Xfriend-paths` is a `-X` compiler flag: stable in practice and
  used widely for test friendship, but not a documented contract. A
  Kotlin upgrade that changes it surfaces as a loud compile error,
  never as silent misbehaviour.
- A lone `-pl <twin>` build does not work; the reactor is the only
  build shape.

**Neutral:**

- The "genuinely differ" line is expected to keep moving; every move
  is recorded below rather than re-argued.
- `ClientLoggingProperties` is source-facing for hosts that import it
  for a custom interceptor or filter bean: its package is
  `eu.inqudium.legatium.common`, decided before the first release.

## History

- **2026-09-03:** original extraction, decided with the project
  (`Traceparent`, `NanoTimeSource`, `CorrelationIdGenerator`,
  `reportQuietly`/`failOpen`, `Mdc.kt`, `HeaderLogProperties`,
  `HeaderValueMasker`, `BodyReadState`, `Timeouts`).
- **2026-09-03:** the `ClientLogField` enum (wire names, per-field
  type guarantee, the `addKeyValue`/`addKeyValueIfPresent`/
  `setCauseIfPresent` builder extensions) had been listed as
  deliberately duplicated, by analogy with limesium, whose twin enums
  genuinely differ (`endpoint_async` exists on one stack only). Here
  the two copies were byte-identical apart from KDoc prose: the field
  family is one cross-stack contract, and the only stack-specific
  fact, the reactive `cancelled` outcome, is a VALUE of
  `adapter_outcome`, not a field. The enum moved to `legatium-common`,
  documented stack-neutrally; `ClientLogFieldTest` binds the ELK
  component template against it ONCE there (the template is a test
  resource of `legatium-common`), and the ELK template's `authority`
  points at the new location.
- **2026-09-03 (second):** the same review found
  `ClientLoggingProperties` byte-identical apart from KDoc wording
  (interceptor vs. filter). The `adapter-logging.*` namespace is one
  cross-stack contract by design, key for key and default for default,
  and unlike limesium there is no stack-only key (`variant`) to
  justify two classes. The class moved to `legatium-common` with
  `ClientLoggingPropertiesTest` and `ClientLoggingReferenceConfigTest`,
  so the reference YAML is bound ONCE and the twins declare no
  shared-docs test resources any more. The two twins' copies of the
  test helper `MdcAdapterSwap` were unused (only `legatium-common`'s
  `MdcScopeTest` swaps the adapter) and were deleted; the "copies are
  cheaper than a test-jar" rule applies to helpers a module actually
  uses.
- **2026-09-04:** finding 10 of
  `docs/assessment/CODE_ANALYSIS-2026-09-04T20-56-15.md` asked for the
  consequences of NOT relocating to be stated; they are now in
  Consequences (duplicate classes under one name, JPMS unsupported for
  both twins). Finding 13 of the same analysis added
  `CorrelationHeader` (ADR-0002).
- **2026-09-04 (second):** finding 1 of
  `docs/assessment/ARCHITECTURE_REVIEW-2026-09-04T21-49-30.md`
  measured the "deliberately duplicated remainder" with stack names
  neutralised: the two `ClientLoggingMetrics` classes were 95 %
  identical (the differences were the outcome list, one tag value and
  two descriptions: data, not design) and the activation logic 96 %.
  Both moved to `legatium-common`: `ClientLoggingMetrics`
  parameterised by a `ClientStack` (the `client` tag, the outcome
  vocabulary, the gauge's wording; the per-registry cache is keyed by
  registry and stack) and `ClientActivation` built from the
  properties. The emitters (72 %), the exchanges and the two
  `BoundedBodyCapture`s remain duplicated. The 90 % threshold became
  the rule, and finding 4 of the same review settled that the
  inlining stays through 1.0.
- **2026-09-05:** finding 3 of
  `docs/assessment/ARCHITECTURE_REVIEW-2026-09-05T00-24-58.md` found
  the evidence for this decision sitting on the wrong side of it.
  Surefire ran the twins' tests in the `test` phase against the
  `legatium-common` module; Shade inlined the classes and wrote the
  dependency-reduced POM afterwards, in `package`, and nothing ever
  loaded the jars a consumer receives. Meanwhile ten of the twelve
  methods in the two `TwinContractTest` files pinned literals of types
  that existed only once in `legatium-common`. The shared literals
  (meter names and fallback tag values, read states, MDC keys, outcome
  vocabulary, fail-open stages, request-id sources) are now pinned
  once in `SharedContractTest`; the registration behaviour of
  `ClientLoggingMetrics` is tested once in `ClientLoggingMetricsTest`
  (ADR-0008); and the packaging is verified by `consumer-smoke/` as
  described above.
- **2026-09-17:** the two `BoundedBodyCapture`s stay duplicated as
  concurrency shells, but the buffer beneath them had become the same
  thing twice: the blocking twin needed an array that a `reset` can cut
  back (the tee stream forwards `mark`/`reset` since the second defect
  round of 2026-09-16), and both twins wanted the array sized once by
  the declared `Content-Length`. `BoundedByteBuffer` moved to
  `legatium-common` with its unit test and fuzz target; each twin keeps
  its count, read state and locking, and the truncation-boundary tests
  stay in the twins as tests of the twin API. `decodeTruncated`, whose
  only caller is now the buffer's rendering, became private to the
  buffer's file.
