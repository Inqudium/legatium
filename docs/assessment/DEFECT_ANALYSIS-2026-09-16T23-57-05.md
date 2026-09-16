# Code & Defect Analysis: Legatium

1. Identification of the codebase
   - **Repository:** `https://github.com/Inqudium/legatium.git`
   - **Commit hash:** `c16d174f132b85a7bf8f31e70d501f0d9df28450` (full)
   - **Reference (branch/tag):** `refs/heads/main`; working tree clean and branch aligned with `origin/main` at analysis start
2. Scope of the analysis
   - **Included production code:** `./legatium-common/src/main/kotlin/`, `./legatium-restclient-logging/src/main/kotlin/`, `./legatium-webclient-logging/src/main/kotlin/`, and the Spring auto-configuration import resources below the twin modules' `src/main/resources/`
   - **Included test code:** **yes, test code is an analysis subject in its own right**: `./legatium-common/src/test/`, `./legatium-restclient-logging/src/test/`, `./legatium-webclient-logging/src/test/`, and `./consumer-smoke/src/test/java/`
   - **Included supporting configuration:** `./pom.xml`, the module POMs, `./consumer-smoke/pom.xml`, and relevant conventions/contracts in `./README.md`, `./docs/adr/`, and the module guides
   - **Excluded:** generated/build output under `./**/target/`; documentation as an independent prose-quality subject; CI/release automation except where needed to understand build and test execution; prior reports in `./docs/assessment/` as findings sources (the previous defect report was consulted only as read-only history and every carried hypothesis was rechecked)
3. Analysis environment & tools
   - **Target/runtime environment:** Java 21 bytecode/runtime baseline; Kotlin 2.4.20; Spring Boot 4.1.1; local inspection JVM Oracle JDK 26.0.1
   - **Build system:** Apache Maven 3.9.15, multi-module reactor plus the standalone `./consumer-smoke/` project
   - **Analysis tools used:** Git, `rg`, `tokei`, manual Kotlin/Java/Spring/Reactor lifecycle and data-flow review; existing JaCoCo/Surefire output was not treated as current verification evidence
   - **Static analysis present in the project:** ktlint 3.7.1 and JaCoCo 0.8.15; no detekt, SpotBugs, Error Prone, NullAway, ArchUnit, or Sonar configuration detected
4. Placement & Output
   - **Working directory (workdir):** `/home/dirk/IdeaProjects/legatium` (absolute reference point; all relative paths refer to it)
   - **Report output path:** `./docs/assessment/DEFECT_ANALYSIS-2026-09-16T23-57-05.md`
   - **Scope root (relative to the workdir):** `./`
   - **Path convention for finding locations:** `<path relative to the workdir>:<line>`

## 1. Executive summary

The current codebase is unusually deliberate about lifecycle ownership, fail-open behavior, bounded body capture, MDC/context restoration, and exactly-once emission. No Critical or High defect was found in the inspected commit. The most important remaining defect is in the blocking response decorator: it wraps every response body in a new `InputStream` but does not preserve the delegate's `mark`/`reset` capability, so logging can change application-visible stream behavior even with body capture disabled. A smaller correctness issue classifies a valid bodyless `HEAD` response as `unread` when it advertises the representation length in `Content-Length`, distorting an opt-in read-state metric. The dynamic body meters also rebuild and re-register meter builders for every measured exchange, producing avoidable allocation and registry/filter work on an opt-in hot path. The earlier open hypotheses around response-read state, fail-open guards, customizer order, global test state, and connector evidence were checked against the current code; the previously implemented corrections are present.

The test suite is a strong safety net for the dominant paths: risk-heavy lifecycle code has dense unit tests, real local-peer integration tests, connector contracts, fuzz regression seeds, deterministic clocks/IDs, and explicit concurrency barriers. The pyramid is healthy rather than inverted: fast unit/contract tests carry most behavior, while Spring/connector tests validate integration seams. Existing local Surefire reports show a broad green suite, but no build or test command was run during this analysis because the governing prompt requires separate go-ahead; those generated reports are therefore not proof for this exact review run.

Most significant test gaps/anomalies:

- No test exercises `markSupported()`, `mark()`, or `reset()` through `CapturingClientHttpResponse`, so the Medium production defect survives the otherwise extensive delegate-failure coverage.
- No blocking-client test covers `HEAD` plus a non-zero representation `Content-Length` with response-body measurement enabled.
- Direct unit ownership remains uneven for a few shared helpers (`ClientIdentity`, `declaredCharsetOrUtf8`, and `decodeTruncated` are primarily proved through the twins), although their important behaviors do have indirect coverage.

## 2. Scope & methodology

The repository contains three reactor modules: unpublished shared code in `./legatium-common/`, a blocking Spring `RestClient`/`RestTemplate` twin in `./legatium-restclient-logging/`, and a reactive WebClient twin in `./legatium-webclient-logging/`. `./consumer-smoke/` is deliberately outside the reactor and checks the shaded artifacts as a Java consumer sees them. The system is a library, not an application: there are no controllers, message listeners, scheduled jobs, persistence repositories, database transactions, Kafka/JMS/Rabbit consumers, or application runners. Its entry points are a `ClientHttpRequestInterceptor`, an `ExchangeFilterFunction`, Spring Boot auto-configurations, and their client customizers.

The blocking twin owns an exchange from interceptor entry until `ClientHttpResponse.close()`. The reactive twin is Reactor-based and owns it from subscription through response handover and then the body publisher's terminal/cancel signal. Kotlin coroutines are not used; Java virtual threads are not configured by the library. Persistence is absent. Optional Micrometer context propagation restores ambient thread-locals around reactive emission, while the blocking twin snapshots caller MDC for cross-thread response closure.

Phase 1 ranked all relevant production and test files before deep review. Phase 2 followed identity, body bytes, read-state, failures, cancellation, metrics, MDC/context, and exactly-once state across module boundaries, starting with score-5 files. Tests were assessed both as production-risk evidence and as defect candidates, using mutation-style questions around assertion strength, determinism, isolation, worker-error propagation, and real connector behavior. Phase 3 checked reachability, framework contracts, ADRs, documentation, and current tests before retaining a finding.

No local build or test execution took place: the supplied analysis procedure says locally buildable findings are to be confirmed only after the user's go-ahead. Consequently finding 1 is verified by the Java `InputStream` contract and a concrete execution sketch, not by a newly added/running reproduction; this analysis made no source changes and created only this report. Existing `./**/target/` output was excluded as generated state and was not used to claim verification.

Known blind spots are connector-specific behavior outside the four WebClient and five blocking request-factory families represented in the tests, unusual third-party `ClientResponse`/Reactive Streams implementations that violate their contracts, and performance magnitude without a profiler or benchmark. Pure security concerns, release infrastructure, prose style, and dependency-vulnerability analysis are outside this report.

## 3. Statistics

| Severity | Findings |
|---|---:|
| Critical | 0 |
| High | 0 |
| Medium | 1 |
| Low | 2 |
| **Total** | **3** |

- **Detected systemic patterns:** 1
- **Categories:** correctness/robustness 1, observability correctness 1, performance 1
- **Test-quality result:** no independent faulty-test finding; two concrete coverage gaps are linked to findings 1 and 2

## 4. File-ranking table

Score 5 denotes lifecycle/concurrency/hot-path ownership; score 4 denotes important supporting logic or high-value safety evidence; score 3 denotes integration/configuration with limited branching; score 2 denotes small deterministic helpers or fixtures; score 1 denotes declarative data/constants.

### Production and build files

| File | Score | Rationale |
|---|---:|---|
| `./pom.xml` | 3 | Reactor, compiler/test/static-analysis/shade lifecycle and dependency management |
| `./legatium-common/pom.xml` | 2 | Shared-module test/fuzz/build wiring |
| `./legatium-restclient-logging/pom.xml` | 3 | Optional Boot APIs, connector test matrix, shading/friend paths |
| `./legatium-webclient-logging/pom.xml` | 3 | Reactor/connectors/context-propagation dependency surface |
| `./consumer-smoke/pom.xml` | 3 | Standalone consumer-side resolution and shaded-artifact boundary |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/ClientLoggingMetrics.kt` | 5 | Shared mutable owner, weak cache, gauge identity, dynamic meter hot path, fail-open registration |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/Mdc.kt` | 4 | Thread-local overlay, rollback, best-effort restoration |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/BodyCapture.kt` | 4 | Byte-boundary decoding and read-state contract |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/ClientActivation.kt` | 4 | Decoded/raw URI boundary, include/exclude precedence |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/ClientIdentity.kt` | 4 | Trace/header/generated identity precedence and wire mutation decision |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/ClientLoggingProperties.kt` | 4 | Binding validation and memory/body limits |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/HeaderLogProperties.kt` | 4 | Confidentiality-sensitive selection/masking precedence |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/HeaderValueMasker.kt` | 4 | Per-header cryptographic hot path and thread-safety |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/CorrelationIdGenerator.kt` | 4 | Concurrent uniqueness, fixed-width overflow boundary |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/Timeouts.kt` | 4 | Cyclic cause/suppressed graph traversal and connector classification |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/Traceparent.kt` | 4 | External-format parser and identity correctness |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/RequestTarget.kt` | 3 | URI authority/IPv6/registry-host normalization |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/FailOpenDiagnostics.kt` | 3 | Interrupt preservation and secondary-failure confinement |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/DeclaredCharset.kt` | 3 | Peer-controlled media-type parsing fallback |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/ClientLogFields.kt` | 2 | Structured logging helpers and fixed wire names |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/AdapterName.kt` | 1 | Trivial normalization |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/BodyLogMode.kt` | 1 | Small exhaustive policy enum |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/Classification.kt` | 1 | Immutable value holder |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/CorrelationHeader.kt` | 2 | Small inbound-value acceptance boundary |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/NanoTimeSource.kt` | 1 | Injected clock interface |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/NoOpScope.kt` | 1 | No-op closeable |
| `./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/ClientRequestLoggingInterceptor.kt` | 5 | Blocking entry point, response ownership, fail-open wiring and exactly-once completion |
| `./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/ExchangeLogEmitter.kt` | 5 | Outcome/level decision, body metrics, MDC and logging failure boundary |
| `./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/CapturingClientHttpResponse.kt` | 5 | Application-visible response/stream decorator and close-time emission |
| `./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/BoundedBodyCapture.kt` | 4 | Cross-thread visibility, size/read-state tracking, bounded copy |
| `./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/CallerMdcSnapshot.kt` | 4 | Cross-thread snapshot restoration and partial rollback |
| `./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/Exchange.kt` | 4 | Volatile lifecycle state and atomic completion |
| `./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/ClientLoggingAutoConfiguration.kt` | 3 | Bean back-off and customizer ordering |
| `./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/ClientRequestLoggingFilter.kt` | 5 | Subscription-scoped wiring and atomic multi-owner lifecycle |
| `./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/ObservedResponse.kt` | 5 | Response-delivery/cancel race and ownership handover |
| `./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/ObservedBody.kt` | 5 | Reactive terminal/cancel classification and late-signal behavior |
| `./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/ExchangeLogEmitter.kt` | 5 | Freeze/emission/context/outcome hot path |
| `./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/CapturingDecorators.kt` | 5 | DataBuffer ownership, request publisher specialization, zero-copy behavior |
| `./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/BoundedBodyCapture.kt` | 5 | Locking and immutable emission snapshot under cancellation races |
| `./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/Exchange.kt` | 4 | Atomic lifecycle state and cross-thread fields |
| `./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/AmbientContextRestorer.kt` | 4 | Optional-classpath linkage and thread-local restoration |
| `./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/ClientLoggingAutoConfiguration.kt` | 3 | Bean back-off and filter ordering |

### Test files

| File(s) | Score | Rationale |
|---|---:|---|
| `./legatium-common/src/test/kotlin/eu/inqudium/legatium/common/ClientLoggingMetricsTest.kt` | 5 | Proves shared-owner, conflicts, dynamic tags and throwing-meter behavior |
| `./legatium-common/src/test/kotlin/eu/inqudium/legatium/common/MdcScopeTest.kt`, `CountingCorrelationIdGeneratorTest.kt` | 5 | Rollback/restore and real contention safety |
| `./legatium-common/src/test/java/eu/inqudium/legatium/common/TraceparentFuzzTest.java`, `HeaderMaskingFuzzTest.java` | 4 | Parser/selection fuzz boundaries with regression corpus |
| `./legatium-common/src/test/kotlin/eu/inqudium/legatium/common/TraceparentTest.kt`, `TimeoutsTest.kt`, `HeaderLogPropertiesTest.kt`, `ClientLoggingPropertiesTest.kt`, `ClientActivationTest.kt`, `RequestTargetTest.kt` | 4 | Boundary-heavy shared contracts |
| `./legatium-common/src/test/kotlin/eu/inqudium/legatium/common/ClientLoggingReferenceConfigTest.kt`, `SharedContractTest.kt`, `ClientLogFieldTest.kt` | 3 | Reference/binary twin contract evidence |
| `./legatium-common/src/test/kotlin/eu/inqudium/legatium/common/CorrelationHeaderTest.kt`, `HeaderValueMaskerTest.kt`, `FailOpenDiagnosticsTest.kt` | 3 | Focused edge and failure-path evidence |
| `./legatium-common/src/test/kotlin/eu/inqudium/legatium/common/BodyLogModeTest.kt` | 2 | Small enum policy proof |
| `./legatium-common/src/test/kotlin/eu/inqudium/legatium/common/CapturedLogger.kt`, `MdcAdapterSwap.kt`, `TraceparentConformanceFixture.kt` | 3 | Global-state/isolation-sensitive helpers |
| `./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/ClientRequestLoggingInterceptorTest.kt` | 5 | Main blocking lifecycle, failures, MDC, retry and exactly-once proof |
| `./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/ClientRequestLoggingMetricsTest.kt` | 5 | Response read-state and metrics lifecycle proof |
| `./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/ClientRequestLoggingInterceptorBodyAndHeaderTest.kt` | 4 | Body/header/charset boundary evidence |
| `./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/RequestFactoryContract.kt` | 5 | Cross-engine behavioral contract including gzip and timeouts |
| `./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/ClientRequestLoggingInterceptorIntegrationTest.kt`, `ClientRequestLoggingTracingIntegrationTest.kt` | 4 | Real peer, Boot and tracing integration |
| `./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/JdkClientRequestFactoryIntegrationTest.kt`, `HttpComponentsRequestFactoryIntegrationTest.kt`, `JettyRequestFactoryIntegrationTest.kt`, `ReactorNettyRequestFactoryIntegrationTest.kt`, `SimpleRequestFactoryIntegrationTest.kt` | 4 | Five concrete engine/request-factory implementations of the shared contract |
| `./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/BoundedBodyCaptureTest.kt`, `CallerMdcSnapshotTest.kt` | 4 | Byte/read-state and cross-thread restoration evidence |
| `./legatium-restclient-logging/src/test/java/eu/inqudium/legatium/restclient/logging/BoundedBodyCaptureFuzzTest.java` | 4 | Bounded-decoding fuzz evidence |
| `./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/ClientLoggingAutoConfigurationTest.kt`, `UriTemplateAttributeTest.kt`, `TwinContractTest.kt` | 3 | Boot/order/private-constant/twin contracts |
| `./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/TestSupport.kt`, `PeerServer.kt`, `Tarpit.kt` | 4 | Resource-owning, timing- and thread-sensitive fixtures |
| `./legatium-webclient-logging/src/test/kotlin/eu/inqudium/legatium/webclient/logging/ClientRequestLoggingFilterTest.kt` | 5 | Main reactive state machine, cancel races, retry and failure proof |
| `./legatium-webclient-logging/src/test/kotlin/eu/inqudium/legatium/webclient/logging/ClientRequestLoggingMetricsTest.kt` | 5 | Metrics under reactive lifecycle and failure injection |
| `./legatium-webclient-logging/src/test/kotlin/eu/inqudium/legatium/webclient/logging/ClientRequestLoggingFilterBodyAndHeaderTest.kt`, `CapturingDecoratorsTest.kt` | 5 | DataBuffer/body ownership, zero-copy, masking and charset evidence |
| `./legatium-webclient-logging/src/test/kotlin/eu/inqudium/legatium/webclient/logging/ConnectorContract.kt` | 5 | Shared real-connector timeout/body/cancel contract |
| `./legatium-webclient-logging/src/test/kotlin/eu/inqudium/legatium/webclient/logging/ClientRequestLoggingFilterIntegrationTest.kt`, `ClientRequestLoggingTracingIntegrationTest.kt` | 4 | Real Reactor Netty/Boot/context/tracing integration |
| `./legatium-webclient-logging/src/test/kotlin/eu/inqudium/legatium/webclient/logging/ReactorNettyConnectorIntegrationTest.kt`, `JdkHttpClientConnectorIntegrationTest.kt`, `JettyConnectorIntegrationTest.kt`, `HttpComponentsConnectorIntegrationTest.kt` | 4 | Four concrete connectors implementing the shared contract |
| `./legatium-webclient-logging/src/test/kotlin/eu/inqudium/legatium/webclient/logging/BoundedBodyCaptureTest.kt`, `AmbientContextRestorerTest.kt` | 4 | Freeze/locking and optional context restoration evidence |
| `./legatium-webclient-logging/src/test/kotlin/eu/inqudium/legatium/webclient/logging/ClientLoggingAutoConfigurationTest.kt`, `UriTemplateAttributeTest.kt`, `TwinContractTest.kt` | 3 | Boot/order/private-constant/twin contracts |
| `./legatium-webclient-logging/src/test/kotlin/eu/inqudium/legatium/webclient/logging/TestSupport.kt`, `AwaitingAppender.kt`, `PeerServer.kt`, `Tarpit.kt`, `IntegrationFixture.kt` | 4 | Async waiting, resource lifecycle and global logger/context isolation |
| `./legatium-webclient-logging/src/test/kotlin/eu/inqudium/legatium/webclient/logging/IntegrationApp.kt` | 1 | Declarative test application |
| `./consumer-smoke/src/test/java/eu/inqudium/legatium/smoke/ShadedTwinsSmokeTest.java` | 5 | Only consumer-side proof after shading and dependency reduction |
| `./consumer-smoke/src/test/java/eu/inqudium/legatium/smoke/SmokeApplication.java` | 1 | Declarative smoke application |

## 5. Findings checklist

### 🔴 Critical

No Critical findings.

### 🟠 High

No High findings.

### 🟡 Medium

- [x] 1. [./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/CapturingClientHttpResponse.kt:77] {Medium} {Confidence: high} {correctness / robustness} The response-body wrapper silently removes the delegate stream's `mark`/`reset` capability
  - Symptom → cause: `getBody()` returns an anonymous bare `InputStream` that delegates `read`, `available`, and `close`, but not `markSupported`, `mark`, or `reset`. The `InputStream` defaults therefore report `false`, ignore `mark`, and throw on `reset`, even when the real stream supports replay. The wrapper is installed for every logged exchange even when body logging and size measurement are both disabled.
  - Triggering condition: an application, message converter, or custom extractor relies on `mark`/`reset` against a supporting engine stream; a concrete execution sketch is a `ByteArrayInputStream`-backed `ClientHttpResponse`, whose `markSupported()` changes from `true` before wrapping to `false` afterwards and whose reset starts throwing.
  - Impact: application-visible behavior changes because the observability library is present; parsers that probe or rewind can fail or consume the wrong bytes. The current suite's extensive read/open/close failure tests do not exercise these three methods.
  - Fix strategy: use a transparent delegating stream shape and explicitly preserve the delegate's mark/reset contract while defining how replayed bytes affect capture/count semantics. Add a contract test with both a mark-capable and a non-mark-capable delegate.
  - **Status:** Fixed (2026-09-17): confirmed - Spring's own `IntrospectingClientHttpResponse` probes `markSupported()` before peeking at the body, so a buffered response lost its rewind through the wrapper. The tee stream now forwards `markSupported`/`mark`/`reset`; a reset rewinds the capture with the stream (`BoundedBodyCapture.mark`/`reset` restore count, buffered length and read state, so replayed bytes count once), a reset the engine refuses is reported like every other refused engine call. The capture's buffer became a lazily grown, cap-bounded array (a `ByteArrayOutputStream` cannot be cut back), and `decodeTruncated` decodes a length without copying. `skip` and the bulk reads keep their defaults through `read`, so an engine's own skip cannot move bytes past the tee. Pinned in `CapturingClientHttpResponseTest` (buffered and engine-like delegate, no capture attached), `BoundedBodyCaptureTest` (mark/reset, growth) and the fuzz test (mark/reset operations); documented in the class KDoc and the module README's twin table.

### 🟢 Low

- [x] 2. [./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/ClientRequestLoggingInterceptor.kt:284] {Low} {Confidence: high} {correctness / observability} A valid `HEAD` response with a non-zero representation length is counted as an unread response body
  - Symptom → cause: `declaredBodyLength` recognizes bodyless status codes and zero `Content-Length`, but does not consider the request method. HTTP permits a `HEAD` response to carry the `Content-Length` that a corresponding `GET` would have while sending no body; the capture is told to expect those bytes and remains `UNREAD` if the caller never opens a body that cannot exist.
  - Triggering condition: blocking `HEAD` call, response-body measurement enabled, status such as 200, non-zero `Content-Length`, and a bodiless consumption path.
  - Impact: `adapter.response.body.read{state="unread"}` reports discarded payload where no payload was allowed, producing a false operational signal; the HTTP call itself remains correct.
  - Fix strategy: include the request method in the declared-body decision and classify `HEAD` as zero body independent of representation length. Pin the behavior with a measurement test for `HEAD` plus non-zero `Content-Length`.
  - **Status:** Fixed (2026-09-17): confirmed (`headForHeaders` and `toBodilessEntity()` on a HEAD close the response unopened, the capture waited for the representation's length). `declaredBodyLength` takes the request method and returns zero for `HEAD` before the status rule; pinned in `ClientRequestLoggingMetricsTest` (200 to a HEAD with `Content-Length: 1234`, closed unopened, counts `complete`); the bodiless list in the KDoc of `BodyReadState.COMPLETE` and Guide §7.4 names the HEAD answer.

- [ ] 3. [./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/ClientLoggingMetrics.kt:248] {Low} {Confidence: high} {performance} Dynamic body meters rebuild and register a meter on every measured exchange
  - Symptom → cause: `responseBodyRead` constructs a `Counter.Builder` and calls `register`, while `recordBodySize` does the same for each request/response `DistributionSummary`, on every update. Micrometer deduplicates the final meter but the builder, tags, `Meter.Id`, filter processing, and registry lookup are repeated in the hot path.
  - Triggering condition: either body-size measurement option enabled, especially at high request rates; up to three dynamic meter resolutions occur per completed exchange.
  - Impact: avoidable allocation and registry/filter CPU in an observability path; correctness is unaffected and the feature is opt-in.
  - Fix strategy: cache the resolved meters by the already bounded/documented tag tuple, with an explicit size/cardinality policy and the same conflict/fail-open behavior. Benchmark before and after because a cache introduces its own memory and concurrency cost.
  - **Status:** Open (2026-09-17), deliberately - the same decision as finding 19 of the 2026-09-16T20-19-34 report: Micrometer's registry is already the deduplicating cache (one `ConcurrentHashMap` lookup on the `Meter.Id`); a second cache would trade the builder, tags and filter-chain allocations for a tuple key, a size policy and its own cardinality bookkeeping, on an opt-in path whose cost has not been measured against the exchange it observes. Revisit with a benchmark.

## 6. Systemic patterns

### P1 — Dynamic body-metric identity is resolved repeatedly instead of once per tag set

- **Occurrences:** 3 update paths: request-body size, response-body size, and response-body read state.
- **Counting basis:** the two callers of `recordBodySize` plus `responseBodyRead`, all inspected in `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/ClientLoggingMetrics.kt`.
- **Pattern:** fixed-tag meters are correctly pre-registered and reused, but all tag-dependent body meters repeat builder/registration work for an identity that Micrometer ultimately deduplicates.
- **Operational significance:** only hosts opting into body measurement pay the cost, but they pay it on every measured exchange and body direction; this is a bounded-performance concern, not a correctness failure.
- **Direction:** introduce one shared, bounded resolution strategy for all three paths rather than three ad-hoc caches, retaining conflict fallback and low-cardinality safeguards.
- **Status:** Open (2026-09-17) - see finding 3.

No further systemic defect pattern was retained. The response-stream transparency problem and the `HEAD` metric error are isolated blocking-twin issues rather than repeated families.
