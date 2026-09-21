# Code & Defect Analysis: Legatium

1. Identification of the codebase
   - **Repository:** `https://github.com/Inqudium/legatium.git`
   - **Commit hash:** `e01e5499b38f6bdfca38396ea95ac8c6a5ab8a11` (full SHA-1)
   - **Reference (branch/tag):** `refs/heads/main`; working tree clean at the start of the analysis
2. Scope of the analysis
   - **Included:** production Kotlin under `./legatium-common/src/main/kotlin/`, `./legatium-restclient-logging/src/main/kotlin/`, and `./legatium-webclient-logging/src/main/kotlin/`; resources under `./legatium-restclient-logging/src/main/resources/` and `./legatium-webclient-logging/src/main/resources/`; build configuration in `./pom.xml`, `./legatium-common/pom.xml`, `./legatium-restclient-logging/pom.xml`, and `./legatium-webclient-logging/pom.xml`; conventions in `./CONTRIBUTING.md`, `./docs/adr/`, and the module guides
   - **Test code included:** **yes**, as an analysis subject: Kotlin and Java tests and fixtures under `./legatium-common/src/test/`, `./legatium-restclient-logging/src/test/`, `./legatium-webclient-logging/src/test/`, and `./consumer-smoke/src/test/`; their value as a safety net was assessed separately from production behavior
   - **Excluded:** generated output under `./**/target/`; the standalone benchmark implementation under `./benchmarks/src/`; security audit; documentation outside the cited conventions and lifecycle statements; old reports under `./docs/assessment/` as unverified sources of findings
3. Analysis environment & tools
   - **Target environment:** Java 21 bytecode, Kotlin 2.4.20, Spring Boot 4.1.1 (both Spring WebClient and RestClient stacks); build requires JDK 24 or newer
   - **Build system:** Apache Maven 3.9.15, three-module reactor; local inspection JVM Oracle JDK 26.0.1
   - **Analysis tools used:** Git, `rg`, `sed`, `unzip -p`, manual Kotlin/Spring/Reactor data-flow and lifecycle review; local Spring Framework 7.0.9 source jar for the response mutation boundary; no build, tests, or benchmarks executed because the analysis procedure requires a separate go-ahead
4. Placement & Output
   - **Working directory (workdir):** `/home/dirk/IdeaProjects/legatium` (absolute reference point; all relative paths refer to it)
   - **Report output path:** `./docs/assessment/DEFECT_ANALYSIS-2026-09-21T10-40-11.md`
   - **Scope root (relative to the workdir):** `./`
   - **Path convention for finding locations:** `./<path relative to the workdir>:<line>`

## 1. Executive summary

Legatium is a library with two outbound HTTP logging adapters: a blocking RestClient/RestTemplate interceptor and a reactive WebClient filter. The current commit follows the previous defect analysis with fixes for its six findings, an architecture cleanup of wiring diagnostics, and a move of shared test fixtures into the common module's test jar. The production change centralizes 13 wiring-failure reports; the interceptor and reactive lifecycle state machines otherwise retain their prior behavior. All six earlier defect findings were checked against the current locations and appear addressed: JDK client cleanup is bounded in the blocking integration suite, the three missing branch tests are present, the reactive duration rule is tested, and the WebClient guide describes the current operators. No current production defect was established by this read-only review. One Low test-quality finding remains: no assertion pins the newly centralised choice to attach or omit a stack trace on wiring breadcrumbs. Its impact is diagnostic loss after a future regression, not a change to outbound calls.

**Test verdict.** The suite is a useful safety net for the high-risk response lifecycle: injected clocks, real connector contracts, fault injection, and explicit cancel and race scenarios give its core assertions evidential value. Fast unit and contract tests carry most checks, with real-engine integration suites at the boundaries; the pyramid is not dependent only on broad Spring tests. The principal residual gaps are:

- Finding 1: the 13-site wiring diagnostic policy has no test of its stack-trace choice.
- An existing, deliberately accepted limit remains in asynchronous `awaitEvents` assertions: they can observe the expected count before a surplus event arrives. This is recorded as a limitation, not a newly established defect.

## 2. Scope & methodology

The three published-code modules contain 41 Kotlin production files. Their entry points are `ClientRequestLoggingInterceptor.intercept`, `ClientRequestLoggingFilter.filter`, and the two Spring auto-configurations and customizers. The blocking adapter owns a response wrapper and emits at close; the reactive adapter uses Reactor operators (`ObservedResponse`, `ObservedBody`) for handover, cancellation, and exactly-once completion. There are no controllers, persistence layer, message consumers, coroutines, or configured virtual threads. Shared state is concentrated in the Micrometer registry owner/cache and in per-exchange atomics; MDC and Reactor Context are restored across emission boundaries. The static tooling declared by the build includes ktlint and JaCoCo, and CI has CodeQL and fuzz regression targets; no detekt, SpotBugs, NullAway, ArchUnit, or Sonar configuration was found.

Phase 1 ranked the files below by lifecycle complexity, state, I/O, error handling, and the weight of their tests. Phase 2 traced the high-ranked paths and read the full production diff from `0ec595b7873780a86a660f9a353ccbbf65cbe252` to the current commit. The previous report was used as an audit trail only: its six findings were checked against current source and tests before being treated as closed. The new `reportWiringFailure` helper and all 13 callers, test-jar POM wiring, migrated fixtures, and affected tests were examined directly. Phase 3 rejected speculative issues where the normal clients do not establish a trigger, including a custom `ClientResponse` implementation throwing during response mutation. No local execution or mutation experiment was performed; conclusions that depend on runtime behavior are therefore limited to static evidence.

## 3. Statistics

| Severity | Findings |
|---|---:|
| Critical | 0 |
| High | 0 |
| Medium | 0 |
| Low | 1 |
| **Total** | **1** |

**Detected systemic patterns:** 2 (one new test gap across 13 wiring call sites, one carried asynchronous-assertion limitation). The scope includes 41 Kotlin production files and 75 Kotlin/Java test source files, counted from the respective `src/main/kotlin` and `src/test` trees. The test sources declare 455 `@Test`, `@ParameterizedTest`, or `@FuzzTest` annotations; parameterized and inherited contracts may execute more cases than that count.

## 4. File-ranking table

Scores are 5 for lifecycle, concurrency, and hot-path code or the tests that guard it; 4 for important boundary code; 3 for integration and configuration; 2 for small deterministic helpers; 1 for passive types. A grouped row gives the same score to each named file. Paths are relative to the workdir.

| File(s) | Score | Rationale |
|---|---:|---|
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/ClientLoggingMetrics.kt`, `BoundedByteBuffer.kt` | 5 | Registry cache, lock ordering, bounded byte arithmetic |
| `./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/ClientRequestLoggingInterceptor.kt`, `CapturingClientHttpResponse.kt`, `ExchangeLogEmitter.kt` | 5 | Blocking call, response-close emission, tee and error paths |
| `./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/ClientRequestLoggingFilter.kt`, `ObservedResponse.kt`, `ObservedBody.kt`, `CapturingDecorators.kt`, `BoundedBodyCapture.kt`, `ExchangeLogEmitter.kt` | 5 | Reactive subscription, cancellation, freeze, and exactly-once emission |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/Mdc.kt`, `HeaderLogProperties.kt`, `HeaderValueMasker.kt`, `ClientLoggingProperties.kt`, `ClientIdentity.kt`, `ClientActivation.kt`, `Timeouts.kt`, `Traceparent.kt` | 4 | Input boundaries, identity, configuration, and thread-local restoration |
| `./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/BoundedBodyCapture.kt`, `CallerMdcSnapshot.kt`, `Exchange.kt` | 4 | Capture and cross-thread state |
| `./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/Exchange.kt`, `AmbientContextRestorer.kt` | 4 | Atomic exchange state and ambient context |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/FailOpenDiagnostics.kt` | 4 | **Changed:** shared 13-site wiring policy; finding 1 |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/RequestTarget.kt`, `BodyCaptures.kt`, `ClientLoggingPropertyOrigins.kt`, `ClientObservationWiring.kt`, `DeclaredCharset.kt`, `CorrelationIdGenerator.kt` | 3 | Boundary and supporting logic with limited branching |
| `./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/ClientLoggingAutoConfiguration.kt`, `./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/ClientLoggingAutoConfiguration.kt`, `./pom.xml`, `./legatium-common/pom.xml`, `./legatium-restclient-logging/pom.xml`, `./legatium-webclient-logging/pom.xml` | 3 | Wiring, reactor and test-jar/classpath configuration |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/ClientLogFields.kt`, `Classification.kt`, `CorrelationHeader.kt` | 2 | Small deterministic wire contracts |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/AdapterName.kt`, `BodyLogMode.kt`, `BodyReadState.kt`, `NanoTimeSource.kt`, `NoOpScope.kt` | 1 | Small types and constants |
| `./legatium-common/src/test/kotlin/eu/inqudium/legatium/common/ClientLoggingMetricsTest.kt`, `BoundedByteBufferTest.kt`, `CountingCorrelationIdGeneratorTest.kt`; `./legatium-common/src/test/java/eu/inqudium/legatium/common/` fuzz targets | 5 | Registry, byte boundary, contention, and independent fuzz oracles |
| `./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/ClientRequestLoggingInterceptorTest.kt`, `ClientRequestLoggingMetricsTest.kt`, `ClientRequestLoggingInterceptorMdcFaultTest.kt`, `RequestFactoryContract.kt`; five request-factory integration suites in the same directory | 5 | Blocking exactly-once, MDC fault paths, and real engines |
| `./legatium-webclient-logging/src/test/kotlin/eu/inqudium/legatium/webclient/logging/ClientRequestLoggingFilterTest.kt`, `ClientRequestLoggingMetricsTest.kt`, `ClientRequestLoggingFilterBodyAndHeaderTest.kt`, `CapturingDecoratorsTest.kt`, `ConnectorContract.kt`; four connector integration suites in the same directory | 5 | Reactive state, cancellation, body tee, and connector contracts |
| `./consumer-smoke/src/test/java/eu/inqudium/legatium/smoke/ShadedTwinsSmokeTest.java` | 5 | Consumer-visible shaded artifacts and auto-configuration |
| `./legatium-common/src/test/kotlin/eu/inqudium/legatium/common/FailOpenDiagnosticsTest.kt`, `MdcScopeTest.kt`, `HeaderLogPropertiesTest.kt`, `ClientActivationTest.kt`, `TimeoutsTest.kt`, `TraceparentTest.kt`, `ClientIdentityTest.kt`, `BodyCapturesTest.kt` | 4 | Shared guard and input-boundary tests; finding 1 touches the first suite |
| `./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/CapturingClientHttpResponseTest.kt`, `BoundedBodyCaptureTest.kt`, `ClientRequestLoggingInterceptorBodyAndHeaderTest.kt`, `ClientRequestLoggingInterceptorIntegrationTest.kt`, `ClientRequestLoggingTracingIntegrationTest.kt`, `TestSupport.kt` | 4 | Response tee, body policy, Boot and tracing seams, engine cleanup |
| `./legatium-webclient-logging/src/test/kotlin/eu/inqudium/legatium/webclient/logging/BoundedBodyCaptureTest.kt`, `ClientRequestLoggingFilterIntegrationTest.kt`, `ClientRequestLoggingTracingIntegrationTest.kt`, `TestSupport.kt`, `AwaitingAppender.kt` | 4 | Freeze and real async emission; surplus-event limit |
| `./legatium-common/src/test/kotlin/eu/inqudium/legatium/common/PeerServer.kt`, `Tarpit.kt`, `CapturedLogger.kt`, `MdcAdapterSwap.kt`, `MeterAssertions.kt` | 4 | **Moved/shared:** network, logger, metrics and JVM-global MDC fixtures |
| Remaining focused test suites under the three `./legatium-*/src/test/kotlin/` trees (auto-configuration, field/config reference, classification, URI-template, twin contracts, ambient restorer) | 3 | Deterministic wiring and literal contracts, lower lifecycle risk |
| `./consumer-smoke/src/test/java/eu/inqudium/legatium/smoke/SmokeApplication.java` and declarative test resources under `./legatium-*/src/test/resources/` | 1 | Passive test setup and fixtures |

## 5. Findings checklist

### 🔴 Critical

No Critical findings.

### 🟠 High

No High findings.

### 🟡 Medium

No Medium findings.

### 🟢 Low

- [x] 1. [./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/FailOpenDiagnostics.kt:123] {Low} {Confidence: high} {test quality} The new `WiringCost` rule is unpinned for stack-trace attachment.
  - Symptom → cause: `LOST_FEATURE` and `DIRTY_TEARDOWN` require a cause on the internal breadcrumb, while `DEGRADED_EVENT` deliberately omits it (`WiringCost` at lines 92-104, `reportWiringFailure` at lines 112-124). `./legatium-common/src/test/kotlin/eu/inqudium/legatium/common/FailOpenDiagnosticsTest.kt` tests only the older `failOpen` helper. Twin fault tests check counters, message text, and some levels, but do not inspect the internal breadcrumb's `throwableProxy`. Removing `setCauseIfPresent` at line 123, or changing any `withStackTrace` flag, would leave those assertions green.
  - Triggering condition: a future regression of the shared helper or enum; a wiring failure then exercises one of its 13 production callers.
  - Impact: an operator loses the stack that locates a failed feature or dirty teardown, or receives avoidable traces for degraded events. The outbound HTTP result remains unchanged.
  - Fix strategy: add a focused test of `reportWiringFailure` for all three costs, asserting level, formatted message, fail-open count, and presence or absence of the log event's cause. Keep the existing twin tests for the caller-specific behavior.
  - **Fixed (2026-09-21):** `FailOpenDiagnosticsTest` pins the rule once for the thirteen callers: a test per `WiringCost` (`@EnumSource`) asserts the `stage=wiring` count, the level, the formatted message with the appended `toString`, and the event's cause present for `LOST_FEATURE` and `DIRTY_TEARDOWN` and absent for `DEGRADED_EVENT`; a second test pins the three constants' level and flag and that there is no fourth; a third proves a throwing breadcrumb appender is confined and the counter still counts. The twin tests keep the caller-specific sentences and levels.

## 6. Systemic patterns

- **P1 — Shared diagnostic policy without a direct oracle (new).** One policy in `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/FailOpenDiagnostics.kt:112` serves **13 production call sites**, counted with `rg 'reportWiringFailure\('` over the three production trees (excluding the helper declaration). Finding 1 records the single test gap rather than repeating it at each caller.
- **P2 — Async event awaits can miss later surplus events (carried limitation).** The reactive test helper at `./legatium-webclient-logging/src/test/kotlin/eu/inqudium/legatium/webclient/logging/AwaitingAppender.kt:30` returns a snapshot when the requested count arrives. A second event emitted later can therefore escape a `single()` assertion. About **25 `awaitEvents(` sites** occur in the reactive test tree and consumer smoke test, counted by `rg` over those two test trees including helper definitions. This was previously recorded as an accepted limitation and is not counted again as a finding; exactly-once behavior also has synchronous unit assertions and CAS-path tests.

This analysis wrote only this new report. Earlier analysis documents and source files were left unchanged.
