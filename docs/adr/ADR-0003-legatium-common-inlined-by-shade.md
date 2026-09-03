# ADR-0003: Byte-identical twin code lives in legatium-common, inlined by Shade

- **Status:** accepted
- **Date:** 2026-09-03
- **Context:** The sibling project limesium started with two deliberately
  duplicated twins, then learned (its ADR-0003 of 2026-08-30, with two
  amendments) that every shared-layer change became a synchronized
  multi-file port in both directions, and extracted the byte-identical
  set into an internal module inlined by Shade - under the constraint
  that consumers keep adding exactly ONE artifact. Legatium is built as
  limesium's twin project and inherits that lesson from day one instead
  of re-learning it.

## Decision

**The byte-identical shared code lives in the `legatium-common` module;
each twin inlines it into its own jar with the Maven Shade plugin;
`legatium-common` itself is never published.**

- **What is shared:** `Traceparent` (with unit, conformance-fixture and
  Jazzer fuzz tests), `NanoTimeSource`, `CorrelationIdGenerator` (with
  the counting default, ADR-0004), `reportQuietly`/`failOpen`, `Mdc.kt`
  (`MdcKeys`/`TraceMdcKeys`/`MdcScope`), `HeaderLogProperties` (selection
  semantics, with unit test and fuzz target) and `HeaderValueMasker` (the
  injectable masking, with the fingerprint default),
  `BodyReadState`/`decodeTruncated`, `Timeouts` (the classification that
  makes `client_outcome=timeout` mean the same thing on both stacks), and -
  since the amendments below - the `ClientLogField` enum with its builder
  extensions and the `ClientLoggingProperties` binding. Package:
  `eu.inqudium.legatium.common`.
- **What deliberately stays duplicated:** everything whose twin copies
  genuinely differ - the metrics (per-stack outcome vocabulary and meter
  descriptions), the emitters, exchanges,
  interceptor/filter, and `BoundedBodyCapture` (two different
  concurrency designs: volatile single-writer on the blocking stack, lock
  and freeze on the reactive one). For those the accepted cost is a
  conscious port in both directions; the lockstep tests
  (`TwinContractTest`, `ClientLogFieldTest`,
  `ClientLoggingReferenceConfigTest`) catch *named* contract drift, not
  behavioural drift.
- **Shading:** an `artifactSet` restricted to `eu.inqudium:
  legatium-common`, NO relocation (relocating rewrites bytecode but not
  Kotlin metadata), `keepDependenciesWithProvidedScope=false` so the
  dependency-reduced POM drops the dependency entirely, and the
  module's `META-INF/maven` filtered out of the shaded jar.
  spring-boot-starter-parent pre-configures an unnamed uber-jar shade
  execution; it is unbound (`phase=none`) so declaring the plugin does
  not swallow the compile classpath.
- **Visibility:** the twins compile with `-Xfriend-paths` (own output
  dir, common's classes dir AND jar - the reactor resolves the dependency
  as a directory before packaging and as a jar afterwards), so the shared
  classes stay `internal`. Consequence: a twin builds only through the
  reactor (from the root, or with `-am`) - a lone `-pl <twin>` build
  resolves the dependency from the local repository, which is not a
  friend path, and fails with "internal in file".
- **Not published:** `maven.deploy.skip=true` plus `skipPublishing=true`
  for the Central Portal bundle. The published twin POMs mention no
  `legatium-common`.
- **Documentation:** each twin's Dokka run includes the common sources
  as an additional source root - the API reference documents what the
  shaded jar actually contains, and cross-module KDoc links resolve
  under `failOnWarning`. The Docs workflow installs (not merely
  verifies) before the per-module Dokka runs, so the dependency
  resolves.

## Consequences

- A shared-layer change is made ONCE; the both-directions port and its
  drift risk exist only for the deliberately duplicated remainder.
- Consumers are unaffected in shape: one artifact, no new transitive
  dependency, internals stay internal.
- Both twin jars carry byte-identical copies of the common classes. An
  application with BOTH twins on the classpath (a servlet host that also
  uses `WebClient` - a supported deployment here, unlike limesium's
  twins) sees benign duplication at equal versions and
  classpath-order-dependent classes at skewed versions: keep the two
  versions equal.
- The common classes carry the `eu.inqudium.legatium.common` package,
  distinct from limesium's `eu.inqudium.limesium.common`, so a host that
  runs limesium AND legatium (the intended pairing) never sees two copies
  of one class name.
- `-Xfriend-paths` is a `-X` compiler flag: stable in practice and used
  widely for test friendship, but not a documented contract; a Kotlin
  upgrade that changes it surfaces as a loud compile error, never as
  silent misbehaviour.

## Amendment (2026-09-03)

The `ClientLogField` enum (wire names, per-field type guarantee, the
`addKeyValue`/`addKeyValueIfPresent`/`setCauseIfPresent` builder
extensions) was listed above as deliberately duplicated, by analogy with
limesium, whose twin enums genuinely differ (`endpoint_async` exists on
one stack only). Here the two copies were byte-identical apart from KDoc
prose: the field family is one cross-stack contract, and the only
stack-specific fact - the reactive `cancelled` outcome - is a VALUE of
`client_outcome`, not a field. The enum now lives in `legatium-common`,
documented stack-neutrally, and `ClientLogFieldTest` binds the ELK
component template against it ONCE there (the template is declared as a
test resource of `legatium-common`); the twins no longer carry the test or
the template resource. The ELK template's `authority` points at the new
location.

## Amendment (2026-09-03, second)

The same review found `ClientLoggingProperties` byte-identical apart from
KDoc wording (interceptor vs. filter) - the `client-logging.*` namespace is
one cross-stack contract by design, key for key and default for default,
and unlike limesium there is no stack-only key (`variant`) to justify two
classes. The class now lives in `legatium-common` (which therefore depends
on `spring-boot` for the `@ConfigurationProperties` annotation - no
autoconfigure, no starter), documented stack-neutrally; both twins'
auto-configurations enable the same class, and a host carrying both twins
gets one properties bean (Boot derives the bean name from prefix and class).
`ClientLoggingPropertiesTest` and `ClientLoggingReferenceConfigTest` moved
along, so the reference YAML is bound ONCE in `legatium-common` and the
twins declare no shared-docs test resources any more. NOTE - source-facing
for hosts that import the class for a custom interceptor/filter bean: the
package is `eu.inqudium.legatium.common`, decided before the first release.
The two twins' copies of the test helper `MdcAdapterSwap` were unused (only
`legatium-common`'s `MdcScopeTest` swaps the adapter) and were deleted; the
"copies are cheaper than a test-jar" rule applies to helpers a module
actually uses.
