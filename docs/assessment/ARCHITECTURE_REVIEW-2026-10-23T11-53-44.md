# Architecture & Appropriateness Analysis: Legatium

1. Identification of the Codebase
   - **Repository:** `https://github.com/Inqudium/legatium.git`
   - **Commit-Hash:** `7deba240bd1eba4e707cdcd2514589e322aef7f4` (Full)
   - **Reference (Branch/Tag):** `refs/heads/main`; no tag on the commit (`git describe`: `1.2.1-61-g7deba24`, development state `1.2.2-SNAPSHOT` after release 1.2.1 of 2026-10-19)
   - **Working Tree:** clean
2. Scope of the Analysis
   - **Included production code:** `./legatium-common/src/main/`, `./legatium-restclient-logging/src/main/`, `./legatium-webclient-logging/src/main/` (43 Kotlin files, 5,939 lines, of which ~2,594 are comment lines)
   - **Included test code (full analysis subject):** `./legatium-common/src/test/`, `./legatium-restclient-logging/src/test/`, `./legatium-webclient-logging/src/test/`, `./consumer-smoke/src/test/`. The test architecture is assessed at full Phase 2 depth, in both roles: as a measuring instrument of the architecture and as an appropriateness subject of its own.
   - **Included architecture/build context:** `./pom.xml`, the three module POMs, `./consumer-smoke/pom.xml`, `./benchmarks/pom.xml`, `./README.md`, `./CONTRIBUTING.md`, `./CHANGELOG.md`, `./docs/GUIDE.md`, `./docs/cancellation-by-engine.md`, the module READMEs, `./docs/adr/` (15 ADRs), `./.github/workflows/`, issue #88
   - **Excluded:** build output (`./target/`, `./*/target/`); `./benchmarks/src/` and `./benchmarks/results/` (only the POM as build context); binary fuzz seeds; earlier documents under `./docs/assessment/` are read-only history, and their results were adopted only after verification at this commit
   - **Documented production scale/SLA/team size:** `[MISSING - please supply]`. Visible facts: one maintainer, releases 1.2.0 and 1.2.1 on Maven Central, and one sibling consumer project (`veredium`). Load profile, latency budget and consumer count are not documented anywhere.
3. Analysis Environment & Tools
   - **Target Environment:** Java 21 bytecode, Kotlin 2.4.21, Spring Boot 4.1.1 (Spring Framework 7.0.9), Micrometer from the Boot BOM
   - **Build system:** Apache Maven 3.9.15 (enforced minimum 3.6.3), multi-module reactor; the build requires JDK 24+, CI runs Temurin 25
   - **Local analysis environment:** Oracle JDK 26.0.1, Linux x86_64
   - **Analysis tools used:** `git diff`/`git log` against the commit of the previous review (`bf68fe362f07056ee73bb03c0a8a3cf570321239`), `grep`/`wc`/a Python count of comment lines, `gh issue view 88`, reading of the changed production units, ADR-0014/0015, the cancellation page and the test contracts; no SonarQube, detekt or IDE inspections
   - **Verification depth:** static; no build was started for this review (the `mvn verify` runs of the same day for the defect and style rounds are the latest build evidence: 610 tests green)
4. Placement & Output
   - **Working directory (workdir):** `/home/dirk/IdeaProjects/legatium` (absolute reference point; all relative paths refer to it)
   - **Report output path:** `./docs/assessment/ARCHITECTURE_REVIEW-2026-10-23T11-53-44.md`
   - **Scope root (relative to the workdir):** `./`
   - **Path convention for findings:** `<path relative to the workdir>:<line>`
   - **Timestamp:** `2026-10-23T11:53:44+02:00`, Europe/Berlin

---

## 1. Executive Summary

Legatium remains a small Spring Boot library whose structure fits its problem: one shared contract in `legatium-common`, inlined by Shade (ADR-0003), a blocking interceptor twin, and a reactive filter twin. All four findings of the previous review (2026-09-21) are resolved at this commit:

- The fail-open wiring block is a single helper (`reportWiringFailure`/`WiringCost`).
- The single-impl test seam `CallerMdcRestorer` is gone.
- The wiring report is bounded by ADR-0014, and its bean signatures have not grown since (7 and 8 parameters).
- The twins' test helpers live once in the common module's test-jar.

The largest change of the cycle is ADR-0015: the blocking twin now logs an abandoned call as `cancelled`. It does so with a small, load-bearing mechanism (`Exchange.recordFailure` reads the interrupt flag, `Cancellations` is the cause-graph fallback, `CauseGraph` is a walk shared with `Timeouts`) that answers a real, documented problem (issue #88: ERROR floods under a `TimeLimiter`). Production grew by only 192 net lines in this cycle, while tests grew by 965 and documentation by 951.

The new knowledge about engines is spread across three places: by-name lists in production, the contract matrix in the tests, and a per-engine operations page. Two Low findings follow from that spread:

- HttpClient 4 support was added without a documented consumer for an engine that Spring no longer ships.
- The new operations page documents engine-internal exception types and versions that no test pins, so it can silently drift with every engine update.

There is no sign of under-engineering, wrong dependency direction, or paradigm mixing. The tendency is not over-engineering in the code; the risk lies in the weight of evidence and documentation around it.

**Compact test verdict.**

*Testability of the architecture: very good.*

- Both entry points can be constructed without a Spring context.
- Time, correlation ID and masker are `fun interface` seams with a real second user (host beans).
- The new cancellation logic can be checked without an engine: an interrupt flag set on the test thread plus hand-built exceptions (`ClientRequestLoggingInterceptorCancellationTest`, `CancellationsTest`, `CauseGraphTest`).

*Utilization and pyramid: healthy and still unit-heavy, but the real-engine top has grown.*

- 483 `@Test`/`@ParameterizedTest`/`@FuzzTest` annotations; only the 14 tests of the two abstract contracts run multiple times, once per engine.
- 74 real-socket cases from the two contracts (9 tests × 6 request factories, 5 × 4 connectors; previously ~50), plus the pool scenarios of the two Apache suites.
- 11 `@SpringBootTest` files, unchanged.
- Test lines to production lines: 2.7 : 1 (previously 2.6 : 1).

*Most significant gaps and anomalies:*

- No untested score-4/5 core; the shade boundary remains covered by `consumer-smoke/`.
- An engine without a known consumer runs the full contract matrix (finding 1).
- The cancellation page claims "this table is updated with them" when the suites change, but an engine change of the thrown type fails no test (finding 2).
- The test helpers in the test-jar (`PeerServer`, `Tarpit`, `CapturedLogger`, `MeterAssertions`, `FailingMdcAdapter`, `installMdcAdapter`) are a justified abstraction with 2 to 3 real users each, not a home-grown framework.

## 2. Problem Baseline & Methodology

### Problem baseline

**What the system does.** Legatium emits exactly one structured `adapter_*` log line per outgoing HTTP exchange. It does so blocking via a `ClientHttpRequestInterceptor` (RestClient/RestTemplate) and reactively via an `ExchangeFilterFunction` (WebClient), and it feeds six meter families into the host's registry. It has no persistence, no messaging, and no business domain. The "domain" is the lifecycle of an HTTP client: where the event is emitted, cancel semantics, identity from `traceparent`, and fail-open behavior. The code mass is distributed as follows: `legatium-common` 26 files / 2,484 lines, RestClient twin 8 / 1,648, WebClient twin 9 / 1,807.

**Real forces.** The forces of the previous review hold unchanged: fail-open into the diagnostic channels, twin symmetry as an invariant (`CONTRIBUTING.md`), lazy cancellable bodies versus close-based emission, and a single artifact per twin (ADR-0003). New in this cycle:

- **Issue #88** (from veredium's defect analysis): an abandoned blocking call landed at ERROR as `failure`. This is a measured operational need under a `TimeLimiter`, answered by ADR-0015.
- **Engine diversity under interrupt:** virtual versus platform threads, future-based versus socket-based waiting. The contract pins this per engine.
- **Pool lease timeouts** of Apache 5 (release 1.2.1) and Apache 4 (unreleased), which were previously classified as `failure`.

**Documented architectural intent.** 15 ADRs, of which two are new: ADR-0014 (the wiring report as a bounded diagnostic, with an admission rule and a revisit signal) and ADR-0015 (blocking `cancelled`, the interrupt beats a timeout, recognition stays in the blocking twin as its only user). ADR-0003 has been amended: test helpers are shared through the common module's test-jar, and the copy exception is revoked. All three are counted below as justifying forces.

**Technology coherence.** Unchanged and clean: `spring-web` in the blocking twin, `spring-webflux`/`reactor-core` in the reactive one, and no paradigm mixing. ADR-0015 deliberately does not copy the reactive mechanism (subscription cancel) into the blocking twin; it uses the blocking stack's native signal, the interrupt flag. That is the right adaptation to the paradigm. Engine knowledge is kept by class name without a compile dependency (`Timeouts`: 7 names; `Cancellations`: 2 types plus 2 type/message pairs). This is consistent with the rule that neither twin gains an engine dependency.

### Test topology and testability as a baseline signal

- **Volume:** 80 test source files with 15,998 lines (including `consumer-smoke`); 483 `@Test`/`@ParameterizedTest`/`@FuzzTest` annotations, 483 rationale blocks.
- **Fakes:** no mocking library; fakes are hand-written (`ObservingExecution`, `RecordingExchange`, `FailingMdcAdapter`, `CapturedLogger`).
- **Shared infrastructure:** the common module's test-jar (`./legatium-common/pom.xml`, consumed by both twins via friend paths).
- **Boot context:** `@SpringBootTest` in 11 files, as before.
- **Real-engine top:** the two contracts, `RequestFactoryContract` (9 tests × 6 factories, among them the test-only adapter `HttpClient4RequestFactory`) and `ConnectorContract` (5 × 4).

The new ADR-0015 tests show the architecture's healthy shape. The order, the flag, the close on another thread and the body gate are proven without an engine. Only *what an engine does with an interrupt* goes to the real-socket top, and it cannot be proven any other way. The pyramid is not inverted; the top has grown in the place where the thing being measured is the engine.

### Methodology

All units were ranked by mismatch risk (section 4) and held against the five appropriateness questions from score 5 down, with the focus on units changed since `bf68fe362f07056ee73bb03c0a8a3cf570321239`. Growth was measured with `git diff --shortstat` per area: production +531/−339, tests +1,635/−670, documentation (GUIDE, ADRs, cancellation page, READMEs, CHANGELOG, CONTRIBUTING) +1,020/−69.

The four findings of the previous review were verified at this commit:

- `reportWiringFailure` has 13 callers.
- `CallerMdcRestorer` no longer exists (`grep` finds 0 hits).
- ADR-0014 exists, and the bean signatures are unchanged (7/8 parameters).
- `PeerServer`/`Tarpit` live only in `./legatium-common/src/test/`.

Pattern P1 of that review ("diagnostics of the diagnostics grows each cycle") was rechecked: `ClientLoggingMetrics.kt` 569 → 562 lines, no new report line. The pattern has come to a halt.

Checked and rejected as findings:

- **`<reporting>` and the plugin declarations of `maven-dependency-plugin`/`versions-maven-plugin` without an execution:** taken from the ecosystem template tabellarium (commits `6268ec0`, `dcb4e9d`). The template is the justifying force.
- **`RequestFactoryContract` as an abstract base class with protected helpers:** it has 6 real subclasses and is not a framework built for one case.
- **`CauseGraph` in common:** 2 real users in two modules.

Blind spots: no runtime measurement, no consumer survey, no knowledge of whether external hosts run HttpClient 4.

## 3. Statistics

| Severity | Count |
|---|---:|
| Critical | 0 |
| High | 0 |
| Medium | 0 |
| Low | 2 |
| **Total** | **2** |
| **Systemic patterns** | **1** |

| Scope | Count |
|---|---:|
| Kotlin production files | 43 |
| Production lines (incl. comments/blank lines) | 5,939 |
| of which comment lines | ~2,594 (44 %) |
| Test sources (Kotlin + Java, incl. `consumer-smoke`) | 80 |
| Test lines | 15,998 |
| `@Test`/`@ParameterizedTest`/`@FuzzTest` annotations | 483 |
| Rationale blocks | 483 |
| `@SpringBootTest` files | 11 |
| Real-engine test cases (contracts × engines) | 74 |
| ADRs | 15 |
| Growth since 2026-09-21 (net lines): production / tests / documentation | +192 / +965 / +951 |

## 4. Ranking Table

| Unit | Score | Rationale |
|---|---:|---|
| `./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/{ClientRequestLoggingFilter,ObservedResponse,ObservedBody,Exchange,BoundedBodyCapture,CapturingDecorators}.kt` | 5 | Reactive hot path with its own operators and a four-state machine; unchanged in this cycle apart from KDoc and member order; still load-bearing, no finding |
| `./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/{ClientRequestLoggingInterceptor,Exchange,ExchangeLogEmitter,Cancellations}.kt` with `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/{CauseGraph,Timeouts}.kt` | 5 | **Changed:** ADR-0015 mechanism; checked: minimal (one sticky `@Volatile` flag, one classification branch, one fallback object, a shared walk), justified by issue #88 and ADR-0015; no finding |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/ClientLoggingMetrics.kt` | 4 | 562 lines; the per-stack outcome list was removed (one vocabulary per ADR-0015), i.e. simpler; ADR-0008; no finding |
| Engine knowledge across production, tests and documentation: `Timeouts.kt`, `Cancellations.kt`, `./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/{RequestFactoryContract,HttpClient4RequestFactory,HttpClient4RequestFactoryIntegrationTest,HttpComponentsRequestFactoryIntegrationTest}.kt`, `./docs/cancellation-by-engine.md` | 4 | **New:** sixth engine, 258-line operations page; findings 1 and 2, pattern P1 |
| Build and distribution boundary: `./pom.xml`, module POMs, Shade, test-jar, `./consumer-smoke/`, `./benchmarks/pom.xml` | 3 | **Changed:** test-jar of `legatium-common`, enforcer, report plugins after the tabellarium template; ADR-0003's revisit conditions not met; no finding |
| Both `ClientLoggingAutoConfiguration.kt` plus `ClientLoggingPropertyOrigins.kt`, `ClientObservationWiring.kt` | 3 | ADR-0014 holds: signatures unchanged, no new report line; no finding |
| Test architecture: test-jar fixtures (`PeerServer`, `Tarpit`, `CapturedLogger`, `MeterAssertions`, `MdcAdapterSwap`), contracts, rationale convention | 3 | Abstraction with 2 to 3 real users per fixture, no framework; no finding |
| Shared boundary types (`ClientIdentity`, `Mdc`, `HeaderLogProperties`, `HeaderValueMasker`, `ClientActivation`, `Traceparent`, `FailOpenDiagnostics`, `BoundedByteBuffer`) | 2 | Stable, cohesive; no finding |
| `ClientLogField`, `BodyReadState`, `AdapterName`, `DeclaredCharset`, `NoOpScope`, enums, `./consumer-smoke/src/` | 1 | Contract carriers and passive types; not examined in depth |

## 5. Findings Checklist

### 🔴 Critical

No findings.

### 🟠 High

No findings.

### 🟡 Medium

No findings.

### 🟢 Low

- [x] 1. [`./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/HttpClient4RequestFactory.kt:1`, `./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/HttpClient4RequestFactoryIntegrationTest.kt:1`, `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/Timeouts.kt:38`, `./pom.xml` (`httpclient4.version`, `dependencyManagement`), `./legatium-common/pom.xml`, `./legatium-restclient-logging/pom.xml`, `./docs/cancellation-by-engine.md` (§2.3, rank 5 of §5)] {Low} {Confidence: medium} {Over-Engineering / Speculative Generality}
  - Support for an engine that Spring no longer ships, without a documented consumer.
    - Actual structure: a test-only `ClientHttpRequestFactory` adapter for Apache HttpClient 4 (98 lines, modelled on Spring 5) runs the full `RequestFactoryContract` (9 tests) plus two pool scenarios (153 lines). `httpclient` 4.5.14 is test-scoped in two modules, managed by its own POM property and `dependencyManagement` entry. Production gains a name in `Timeouts`, and the operations page gains a section and a ranking row.
    - Solved problem / justifying force: the commit (`30650c3`) and the CHANGELOG argue that "a host still on it bridges it with an adapter of its own". There is no issue, no consumer and no ADR behind it; issue #88 concerns HttpClient 5 and the engines Spring ships. The suite did find a real gap (the pool lease timeout without a cause classified as `failure`), and the `Timeouts` entry that closes it is cheap and right regardless. The rest is preparation for a host nobody has named.
    - Cost: an end-of-life dependency (the POM comment itself says "maintenance releases only") that Dependabot and OSV track; 11 more real-socket cases in every `verify`; an adapter that a maintainer must keep aligned with Spring's `ClientHttpRequest` contract; a documentation row that has to be re-measured. It is small but recurring, and it repeats with every further engine "a host might bridge".
    - Simpler alternative: keep the `Timeouts` entry together with its unit test against the real class (`./legatium-common/src/test/kotlin/eu/inqudium/legatium/common/TimeoutsTest.kt`, already present), but drop the HttpClient 4 adapter from the contract matrix and the ranking until a host asks for it. Alternatively, record the decision "we also prove engines Spring no longer ships" as a sentence with its trigger in ADR-0015 or in the CONTRIBUTING engine rule, so that the next engine meets a rule rather than a precedent.
    - Reversibility: trivial (test code, two POM entries, a documentation section, not released). Leaving it is also defensible; this is backlog.
  - **Fixed (2026-10-23), by the maintainer's decision:** the HttpClient 4 tests only served to observe the behaviour that `./docs/cancellation-by-engine.md` documents, so the code goes and the documentation stays. `HttpClient4RequestFactory` and `HttpClient4RequestFactoryIntegrationTest` are removed (11 real-socket cases less: 599 tests), and so is the `httpclient` test dependency of `./legatium-restclient-logging/pom.xml`. The production entry in `Timeouts` and its unit test against the real classes stay, and so does the test-scoped `httpclient` in `./legatium-common/pom.xml` with the root POM's version property, whose comment now names that test as the only user. The cancellation page (introduction, §2.3, §7) and the RestClient guide mark the HttpClient 4 results as a one-off observation of 2026-10-21 that the build does not re-check, and the CHANGELOG `[Unreleased]` entry now describes it as documentation.

- [x] 2. [`./docs/cancellation-by-engine.md:22`, `:161-180` (ranking with "Tested version" and "Thrown to the caller"), `:255-258`; against `./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/RequestFactoryContract.kt` (`assertCancelled`: only `thrown isNotNull`) and `./CONTRIBUTING.md` (engine suites do not pin exception types)] {Low} {Confidence: medium} {Consistency / Under-Engineering}
  - An operations page documents engine internals that no test protects, and promises an update path that is not triggered.
    - Actual structure: the 258-line page names, per engine, the exception thrown to the caller (`ResourceAccessException` against Apache 5's raw `CancellationException` on virtual threads), retry counts ("after three internal retries"), and the tested versions (jetty-client 12.1.12, reactor-netty-http 1.3.7, httpclient5 5.6.4, JDK 26.0.1). §5 says: "A dependency upgrade can change an engine's behaviour; the suites then fail or show a changed breadcrumb, and this table is updated with them." By convention the suites pin only the classification, and a changed breadcrumb fails no test.
    - Solved problem / justifying force: the page answers a real operator question (which engine frees a worker under a `TimeLimiter`, and which exception the caller sees). The classification columns are pinned by the contract. The "Thrown to the caller" and "Tested version" columns are observations without a guard, and the versions move weekly through Dependabot and the Boot BOM. The page was also measured on JDK 26.0.1 locally, while CI runs JDK 25.
    - Cost: the caller-facing column is the operationally most important statement of the page ("catch `CancellationException`", §4). After an Apache or Boot upgrade it can be wrong without anyone noticing, and the page's own wording suggests the opposite.
    - Simpler alternative: correct the sentence in §5 (an exception change is *not* noticed by the suites), date the observation columns ("observed on 2026-10-21 with …") instead of presenting them as current, and either reduce the "Thrown to the caller" column to the classification or tie its re-measurement to an explicit point in the release checklist. Pinning the thrown type in the suites would contradict the CONTRIBUTING rule and is not recommended.
    - Reversibility: documentation only, trivial.
  - **Fixed (2026-10-23), by the maintainer's decision - pinned instead of dated:** the page's promise is made true rather than withdrawn. `RequestFactoryContract` gains `abandonedCallExceptions` (per engine: the exact type at the interceptor, with message prefix and direct cause where §2.2 names them, and the exact type thrown to the caller), an `EngineThrowRecorder` registered inside legatium's interceptor, and three scenarios (virtual thread, platform thread, `Future.cancel(true)`); the HttpComponents suite adds the interrupted pool lease - 16 new tests, all green against the documented values on the first run, and a deliberately wrong expectation turns exactly its three scenarios red. The classification tests stay independent of the exception types. CONTRIBUTING records the exception to its engine-exception rule, ADR-0015 its history entry; the page's §5 and §7 now describe what is pinned (the HttpClient 4 row stays a dated observation, the Apache retry counts are not pinned).

## 6. Systemic Patterns

- **P1 – Engine knowledge in three places grows per engine.** Counting basis:
  - **Production:** 7 timeout class names in `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/Timeouts.kt` covering 5 engine families, and 4 cancellation markers in `./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/Cancellations.kt`.
  - **Tests:** 6 subclasses of `RequestFactoryContract` and 4 of `ConnectorContract` (74 real-socket cases), plus 2 Apache pool suites.
  - **Documentation:** 6 rows each in §2.2 and §5 of `./docs/cancellation-by-engine.md`, plus the connector notes in the module guides.

  Every new engine or engine quirk touches all three places. Only the first two are guarded by tests; the third is not (finding 2). In production the by-name lists are the cheapest form that is right: they carry no dependency, are documented, and are tested against the real classes. They are therefore not a finding. The pattern names a direction: this cycle's growth went 5:1 into tests and documentation compared with production. Strategy: an explicit rule for which engines are proven (Spring-shipped engines, plus those a consumer names; finding 1), and a fixed point in the release process at which the engine page is re-measured or its observation columns are dated (finding 2).

Checked and not counted as patterns:

- **Comment share of 44 %:** a decided convention (ADR-0013), controlled by its own audit process.
- **Rationale prose (483 blocks):** an ecosystem convention.
- **The deliberately duplicated twin files:** below the 90 % threshold, consistent with ADR-0003.
- **The three public `fun interface`s:** each has a real second user and is no mockability seam.

The highest production complexity, the reactive operators and the ADR-0015 mechanism, is load-bearing and is not a pattern of over-engineering.

---

## 7. Resolution (2026-10-23, branch `fix/architecture-review-2026-10-23`)

- **1:** fixed (see the finding). Verified by `mvn clean verify` (599 tests, 0 failures, ktlint green) and Dokka with `failOnWarning`.
- **2:** fixed (see the finding): the exceptions are pinned per engine, 16 new tests.

---

*No code was changed by this analysis. The only write operation is this report; every "Simpler alternative" describes a strategy to be applied or declined per finding in a separate session.*
