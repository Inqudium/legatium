# Code & Defect Analysis: Legatium

1. Identification of the codebase
   - **Repository:** `https://github.com/Inqudium/legatium.git`
   - **Commit hash:** `b4af4a5424bb710d5f36f8dae1dad6fdcad11e67` (full SHA-1)
   - **Reference (branch/tag):** `refs/heads/main` (`git describe`: `1.2.1-57-gb4af4a5`); working tree clean at the start of the analysis
2. Scope of the analysis
   - **Included:** production Kotlin under `./legatium-common/src/main/kotlin/`, `./legatium-restclient-logging/src/main/kotlin/`, and `./legatium-webclient-logging/src/main/kotlin/`; build configuration in `./pom.xml`, `./legatium-common/pom.xml`, `./legatium-restclient-logging/pom.xml`, `./legatium-webclient-logging/pom.xml`, and `./consumer-smoke/pom.xml`; conventions in `./CONTRIBUTING.md`, `./docs/adr/` (ADR-0015 in particular), `./docs/GUIDE.md`, `./docs/cancellation-by-engine.md`, and the module READMEs
   - **Test code included:** **yes**, as an analysis subject: Kotlin and Java tests and fixtures under `./legatium-common/src/test/`, `./legatium-restclient-logging/src/test/`, `./legatium-webclient-logging/src/test/`, and `./consumer-smoke/src/test/`
   - **Excluded:** generated output under `./**/target/`; the benchmark implementation under `./benchmarks/src/`; security audit; earlier reports under `./docs/assessment/` as unverified sources of findings
3. Analysis environment & tools
   - **Target environment:** Java 21 bytecode, Kotlin 2.4.21, Spring Boot 4.1.1 (Spring Framework 7, RestClient/RestTemplate and WebClient stacks); the build requires JDK 24 or newer
   - **Build system:** Apache Maven 3.9.15 (enforced minimum 3.6.3), three-module reactor plus `./consumer-smoke/`; local inspection JVM Oracle JDK 26.0.1
   - **Analysis tools used:** Git (`git diff` against the commit of the previous defect analysis), `grep`, `sed`, `unzip` of the Apache HttpClient 5.6.4 source jar from the local Maven repository (to verify the `Request aborted` paths), manual Kotlin/Spring/Reactor data-flow and lifecycle review; no build, tests, or mutation experiments were run, because local verification requires a separate go-ahead
4. Placement & Output
   - **Working directory (workdir):** `/home/dirk/IdeaProjects/legatium` (absolute reference point; all relative paths refer to it)
   - **Report output path:** `./docs/assessment/DEFECT_ANALYSIS-2026-10-23T09-59-19.md`
   - **Scope root (relative to the workdir):** `./`
   - **Path convention for finding locations:** `./<path relative to the workdir>:<line>`

## 1. Executive summary

Legatium ships two outbound HTTP logging adapters, a blocking RestClient/RestTemplate interceptor and a reactive WebClient filter, on a shared common module. Since the previous defect analysis (commit `e01e5499b38f6bdfca38396ea95ac8c6a5ab8a11`), production code changed in eight files. The main change is ADR-0015: the blocking twin now classifies a call its caller abandoned (an interrupted thread, or an interrupt in the cause chain) as `cancelled` at WARN, through the new `Cancellations` object and `Exchange.recordFailure`. Further changes: `Timeouts` recognizes the pool lease timeouts of Apache HttpComponents 5 and HttpClient 4 by name, and the metrics owner pre-registers the whole outcome vocabulary on both stacks. The new classification was traced against ADR-0015, Spring's request factories and the HttpClient 5.6.4 sources. The interrupt flag is read and never cleared, it is sticky across the close on another thread, it is checked before the timeout rule, and the cause-less `Request aborted` of HttpClient 5 arises only from an explicitly cancelled request, which is also an abandonment. No Critical, High or Medium defect was established. Two Low findings remain: two KDoc passages still describe the vocabulary as it was before ADR-0015, and the cause-graph walk now exists as two verbatim copies that differ only in the predicate. The other production files are unchanged since the commit of the previous analysis. For them, the verified result of that analysis (no open finding) was rechecked against the diff.

**Test verdict.** The suite remains a reliable safety net for the risk paths, and the new cancellation logic is well guarded. `CancellationsTest` covers every match rule with controls, including `InterruptedIOException`, the timeout subtypes and HttpClient 5's `Request execution failed`, as well as the cyclic cause graph. `ClientRequestLoggingInterceptorCancellationTest` pins the order (cancellation before timeout), the flag after the emission, the body read on an interrupted thread, the answered call and the body gate. It resets the flag in `@AfterEach`. `RequestFactoryContract` runs the virtual-thread, platform-thread and `Future.cancel(true)` scenarios against all six engines with real sockets. The shape of the pyramid is unchanged: fast deterministic unit and contract tests carry the load, and real-engine suites sit at the boundaries.

- No untested score-4/5 path and no assertion-less or tautological test was found among the new tests.
- Residual timing assumption (limitation, not a finding): the interrupt scenarios of `RequestFactoryContract` must interrupt within the 1.5 s of the `/slow` route after the peer has received the request. A runner stalled for longer would turn the expected `cancelled` into `success`. The margin is wide; there is no evidence of a flake.
- Carried limitation P2 of the previous report (async `awaitEvents` can miss a surplus event after the expected count) still applies, at 26 sites.

## 2. Scope & methodology

The three published modules contain 42 Kotlin production files (one more than in the previous analysis: `Cancellations.kt`). Entry points are `ClientRequestLoggingInterceptor.intercept`, `ClientRequestLoggingFilter.filter`, and the two Spring auto-configurations and customizers. There are no controllers, persistence, message consumers or coroutines, and the library does not enable virtual threads itself. The new cancellation tests, however, run the blocking twin on virtual threads, because that is where the JDK makes socket I/O interruptible. Stack: Spring Boot 4.1.1, both Spring MVC client (blocking) and WebFlux client (Reactor). Static analysis declared in the build: ktlint and JaCoCo; CI adds CodeQL, OSV and Jazzer fuzz regression. No detekt, SpotBugs, NullAway, ArchUnit or Sonar.

Phase 1 ranked the files (section 4). Phase 2 read the complete production diff `e01e5499b38f6bdfca38396ea95ac8c6a5ab8a11..b4af4a5424bb710d5f36f8dae1dad6fdcad11e67`: `BodyLogMode.kt`, `ClientLogFields.kt`, `ClientLoggingMetrics.kt`, `Timeouts.kt`, `Cancellations.kt`, `ClientRequestLoggingInterceptor.kt`, `Exchange.kt`, `ExchangeLogEmitter.kt`. It also read the new or changed tests (`CancellationsTest`, `ClientRequestLoggingInterceptorCancellationTest`, `RequestFactoryContract`, `HttpClient4RequestFactory`, `HttpClient4RequestFactoryIntegrationTest`, `HttpComponentsRequestFactoryIntegrationTest`, `TimeoutsTest`, both `TwinContractTest`s, the WebClient `HttpComponentsConnectorIntegrationTest`) and checked the consumers of the old `failure` setter and of `ClientStack.outcomes`. Phase 3 checked every hypothesis against the code paths it would need. Rejected as findings, among others:

- A cause-less `RequestFailedException("Request aborted")` misread as `cancelled`. In HttpClient 5.6.4 it arises only through `isExecutionAborted()`, that is, when someone cancelled the request; that is also an abandonment.
- A pending interrupt from unrelated host code. ADR-0015 accepts it explicitly under "Negative".
- The non-atomic pair `failure`/`interrupted`. Writer order and reader order rule out a mixed state, except for a body read racing a close on another thread, a race the emission at close already has regardless.
- The fixed `Thread.sleep(SHORT × 2)` in the WebClient pool lease test. The lease is requested synchronously on subscription, so the sleep always exceeds the 200 ms deadline.

Limitations: no local execution and no mutation runs. The behavior of individual engines under interrupt was taken from the tests and ADR-0015, not reproduced.

## 3. Statistics

| Severity | Findings |
|---|---:|
| Critical | 0 |
| High | 0 |
| Medium | 0 |
| Low | 2 |
| **Total** | **2** |

**Detected systemic patterns:** 2 (P1 new: duplicated cause-graph walk; P2 carried: async event awaits). Basis: 42 Kotlin production files, 79 Kotlin/Java test source files, 480 `@Test`/`@ParameterizedTest`/`@FuzzTest` annotations, counted with `find`/`grep` over the respective `src/main` and `src/test` trees.

## 4. File-ranking table

Scores: 5 for lifecycle, concurrency and hot-path code or the tests that guard it; 4 for important boundary code; 3 for integration and configuration; 2 for small deterministic helpers; 1 for passive types. **Changed** marks files with a diff since the previous analysis.

| File(s) | Score | Rationale |
|---|---:|---|
| `./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/ClientRequestLoggingInterceptor.kt`, `CapturingClientHttpResponse.kt`, `ExchangeLogEmitter.kt` | 5 | **Changed** (interceptor, emitter): blocking call, emission at close, new classification order |
| `./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/Exchange.kt` | 5 | **Changed:** `recordFailure`, sticky interrupt flag across threads |
| `./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/Cancellations.kt` | 4 | **New:** cause-graph walk, name and message match; finding 2 |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/ClientLoggingMetrics.kt`, `BoundedByteBuffer.kt` | 5 | **Changed** (metrics): registry cache, pre-registration of the whole vocabulary |
| `./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/ClientRequestLoggingFilter.kt`, `ObservedResponse.kt`, `ObservedBody.kt`, `CapturingDecorators.kt`, `BoundedBodyCapture.kt`, `ExchangeLogEmitter.kt` | 5 | Reactive subscription, cancellation and exactly-once emission; finding 1 (`ObservedBody.kt`) |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/Timeouts.kt` | 4 | **Changed:** two more timeout names; finding 2 |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/Mdc.kt`, `HeaderLogProperties.kt`, `HeaderValueMasker.kt`, `ClientLoggingProperties.kt`, `ClientIdentity.kt`, `ClientActivation.kt`, `Traceparent.kt`, `FailOpenDiagnostics.kt` | 4 | Input boundaries, configuration, thread-local restoration, wiring diagnostics |
| `./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/BoundedBodyCapture.kt`, `CallerMdcSnapshot.kt`; `./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/Exchange.kt`, `AmbientContextRestorer.kt` | 4 | Capture and cross-thread state |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/RequestTarget.kt`, `BodyCaptures.kt`, `ClientLoggingPropertyOrigins.kt`, `ClientObservationWiring.kt`, `DeclaredCharset.kt`, `CorrelationIdGenerator.kt` | 3 | Supporting logic with limited branching |
| Both `ClientLoggingAutoConfiguration.kt`; `./pom.xml`, the module POMs, `./consumer-smoke/pom.xml` | 3 | Wiring; **changed** POMs (enforcer, dependency/versions plugins, test-scoped HttpClient 4) |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/ClientLogFields.kt`, `Classification.kt`, `CorrelationHeader.kt`, `BodyLogMode.kt` | 2 | Wire contracts; **changed** (KDoc only: `ClientLogFields`, `BodyLogMode`) |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/AdapterName.kt`, `BodyReadState.kt`, `NanoTimeSource.kt`, `NoOpScope.kt` | 1 | Small types and constants |
| `./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/ClientRequestLoggingInterceptorCancellationTest.kt`, `CancellationsTest.kt`, `RequestFactoryContract.kt` and the six request-factory integration suites (including `HttpClient4RequestFactoryIntegrationTest.kt`) | 5 | **New/changed:** proof of ADR-0015 against real engines and threads |
| `./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/ClientRequestLoggingInterceptorTest.kt`, `ClientRequestLoggingMetricsTest.kt`, `ClientRequestLoggingInterceptorMdcFaultTest.kt` | 5 | Blocking exactly-once, MDC fault paths |
| `./legatium-webclient-logging/src/test/kotlin/eu/inqudium/legatium/webclient/logging/ClientRequestLoggingFilterTest.kt`, `ClientRequestLoggingMetricsTest.kt`, `CapturingDecoratorsTest.kt`, `ConnectorContract.kt` and the four connector suites | 5 | Reactive state, cancellation, connector contracts; `HttpComponentsConnectorIntegrationTest.kt` **changed** |
| `./legatium-common/src/test/kotlin/eu/inqudium/legatium/common/ClientLoggingMetricsTest.kt`, `BoundedByteBufferTest.kt`, `TimeoutsTest.kt`, `FailOpenDiagnosticsTest.kt`; `./legatium-common/src/test/java/eu/inqudium/legatium/common/` fuzz targets | 5 | Registry, byte boundary, timeout recognition (**changed**), independent fuzz oracles |
| `./consumer-smoke/src/test/java/eu/inqudium/legatium/smoke/ShadedTwinsSmokeTest.java` | 5 | Shaded artifacts from the consumer's view |
| `./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/HttpClient4RequestFactory.kt` | 4 | **New:** test adapter; resource lifecycle of the pool in the suite |
| Both `TwinContractTest.kt` | 4 | **Changed:** literal pins of the shared `cancelled` disposition |
| `./legatium-common/src/test/kotlin/eu/inqudium/legatium/common/PeerServer.kt`, `Tarpit.kt`, `CapturedLogger.kt`, `MdcAdapterSwap.kt`, `MeterAssertions.kt`; both `TestSupport.kt`, `./legatium-webclient-logging/src/test/kotlin/eu/inqudium/legatium/webclient/logging/AwaitingAppender.kt` | 4 | Network, logger and JVM-global fixtures; P2 |
| Other focused test suites in the three `./legatium-*/src/test/kotlin/` trees | 3 | Deterministic wiring and literal contracts |
| `./consumer-smoke/src/test/java/eu/inqudium/legatium/smoke/SmokeApplication.java`, test resources under `./legatium-*/src/test/resources/` | 1 | Passive setup |

## 5. Findings checklist

### 🔴 Critical

No Critical findings.

### 🟠 High

No High findings.

### 🟡 Medium

No Medium findings.

### 🟢 Low

- [x] 1. [./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/ObservedBody.kt:33; ./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/CapturingClientHttpResponse.kt:39] {Low} {Confidence: high} {maintainability}
  - Symptom → cause: two KDoc passages describe the classification as it was before ADR-0015. `ObservedBody.kt:33` calls the abandoned subscription "the reactive reality a blocking call cannot have". Since ADR-0015, the blocking twin logs exactly that situation as `cancelled`, and `ClientLogFields`/`ClientOutcome` were reworded accordingly, but this passage was not. `CapturingClientHttpResponse.kt:39` says a failure reported through `onFailure` makes the exchange classify "as a failure or timeout". Since `onFailure = exchange::recordFailure`, it can also be `cancelled` (body read on an interrupted thread, `ClientRequestLoggingInterceptorCancellationTest` line 153).
    - Triggering condition: always (documentation of the current behavior)
    - Impact: maintainability. A reader of the reactive twin draws a wrong conclusion about the twin contract, and a maintainer of the blocking tee misses the third outcome when changing it; runtime behavior is unaffected.
  - Fix strategy: reword both passages to the ADR-0015 vocabulary ("an abandonment, which the blocking twin reports as `cancelled` too" and "failure, timeout or cancelled") and cite ADR-0015.
  - **Fixed (2026-10-23):** `ObservedBody` names the abandonment as the disposition the blocking twin writes for an interrupted caller too, and `CapturingClientHttpResponse` lists `cancelled` on an interrupted thread next to failure and timeout; both cite ADR-0015.
- [x] 2. [./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/Cancellations.kt:47; ./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/Timeouts.kt:46] {Low} {Confidence: medium} {maintainability}
  - Symptom → cause: `Cancellations.isCancellation` copies the cause-graph walk of `Timeouts.isTimeout` line for line (visited `HashSet`, `ArrayDeque`, cause plus suppressed, superclass sequence). Only the predicate differs. Both walks decide, one after the other, the same exchange in `ExchangeLogEmitter.classify`. A later fix to one walk (identity instead of `equals` for the visited set, a depth limit, a different handling of suppressed exceptions) does not reach the other, and the two classifications drift apart for the same exception. ADR-0003/ADR-0015 put the recognition deliberately in the blocking twin, but that concerns the vocabulary of names, not the walk.
    - Triggering condition: only on a future change to one of the two walks
    - Impact: maintainability (duplication with divergence risk); no defect today
  - Fix strategy: offer the walk once in `legatium-common` as an internal function with a predicate (e.g. "any throwable in the cause graph"), on which `Timeouts` and `Cancellations` keep only their own name sets.
  - **Fixed (2026-10-23):** the walk lives once in `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/CauseGraph.kt` (`CauseGraph.any` with a predicate, `CauseGraph.hasTypeNamed` for the by-name hierarchy match); `Timeouts.isTimeout` and `Cancellations.isCancellation` keep only their names and messages. `CauseGraphTest` pins the reach of the walk (root, causes, suppressed on any link, causes of suppressed), the short-circuit, null, the cycle bound and the hierarchy match; `TimeoutsTest` and `CancellationsTest` stay unchanged and green.

## 6. Systemic patterns

- **P1 — Duplicated cause-graph walk (new).** The algorithm exists **2 times** (`Timeouts.isTimeout`, `Cancellations.isCancellation`), counted via `grep -rn 'val visited = HashSet<Throwable>()'` over the three `src/main` trees. Recorded once as finding 2.
- **P2 — Async event awaits can miss later surplus events (carried limitation).** `./legatium-webclient-logging/src/test/kotlin/eu/inqudium/legatium/webclient/logging/AwaitingAppender.kt:30` returns as soon as the expected count has arrived; a later second event escapes a `single()` assertion. **26 `awaitEvents(` sites**, counted via `grep -rn` over `./legatium-webclient-logging/src/test/` and `./consumer-smoke/src/test/` including the helper definition. As before, recorded as an accepted limitation, not as a finding; exactly-once behavior is additionally pinned by synchronous unit tests and the CAS-path tests.

This analysis wrote only this new report. Earlier analysis documents and source files were left unchanged.
