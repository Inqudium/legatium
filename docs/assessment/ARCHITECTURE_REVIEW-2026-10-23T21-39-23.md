# Architecture & Appropriateness Analysis: Legatium

1. Identification of the Codebase
   - **Repository:** `https://github.com/Inqudium/legatium.git`
   - **Commit-Hash:** `3915470bf168b6864beb7338b55e382a455fc460` (Full)
   - **Reference (Branch/Tag):** `refs/heads/main`; no tag on the commit (`git describe`: `1.3.0-6-g3915470`, development state `1.3.1-SNAPSHOT` after release 1.3.0 of 2026-10-23)
   - **Working Tree:** clean
2. Scope of the Analysis
   - **Included production code:** `./legatium-common/src/main/`, `./legatium-restclient-logging/src/main/`, `./legatium-webclient-logging/src/main/` (43 Kotlin files, 6,048 lines, of which ~2,686 are comment lines)
   - **Included test code (full analysis subject):** `./legatium-common/src/test/`, `./legatium-restclient-logging/src/test/`, `./legatium-webclient-logging/src/test/`, `./consumer-smoke/src/test/`. The test architecture is assessed at full Phase 2 depth, in both roles: as a measuring instrument of the architecture and as an appropriateness subject of its own.
   - **Included architecture/build context:** `./pom.xml`, the module POMs, `./README.md`, `./CONTRIBUTING.md`, `./CHANGELOG.md`, `./docs/cancellation-by-engine.md`, `./docs/adr/` (15 ADRs); for one claim the Spring Framework 7.0.9 sources (`ReactorClientHttpRequest`, `SubscriberInputStream`) from the local Maven repository
   - **Excluded:** build output (`./target/`, `./*/target/`); `./benchmarks/src/` and `./benchmarks/results/`; binary fuzz seeds; earlier documents under `./docs/assessment/` are read-only history, and their results were adopted only after verification at this commit
   - **Documented production scale/SLA/team size:** `[MISSING - please supply]`. Visible facts: one maintainer, releases up to 1.3.0 on Maven Central, one sibling consumer project (`veredium`). Load profile, latency budget and consumer count are not documented.
3. Analysis Environment & Tools
   - **Target Environment:** Java 21 bytecode, Kotlin 2.4.21, Spring Boot 4.1.1 (Spring Framework 7.0.9), Micrometer from the Boot BOM
   - **Build system:** Apache Maven 3.9.15, multi-module reactor; the build requires JDK 24+, CI runs Temurin 25
   - **Local analysis environment:** Oracle JDK 26.0.1, Linux x86_64
   - **Analysis tools used:** `git log`/`git diff` against the commit of the previous review (`7deba240bd1eba4e707cdcd2514589e322aef7f4`), a non-comment filter over the production diff, `grep`/`wc`/a Python count of comment lines, reading of the changed units, the engine suites and the cancellation page; no SonarQube, detekt or IDE inspections
   - **Verification depth:** static; no build was started for this review (the CI runs of PRs #108 to #114, all green, are the latest build evidence)
4. Placement & Output
   - **Working directory (workdir):** `/home/dirk/IdeaProjects/legatium` (absolute reference point; all relative paths refer to it)
   - **Report output path:** `./docs/assessment/ARCHITECTURE_REVIEW-2026-10-23T21-39-23.md`
   - **Scope root (relative to the workdir):** `./`
   - **Path convention for findings:** `<path relative to the workdir>:<line>`
   - **Timestamp:** `2026-10-23T21:39:23+02:00`, Europe/Berlin

---

## 1. Executive Summary

This is a follow-up to the review of the same morning (`./docs/assessment/ARCHITECTURE_REVIEW-2026-10-23T11-53-44.md`). Since then, both of its Low findings were fixed (PRs #108, #109), the comment audit of the day was applied (#110), 1.3.0 was released, and two documentation clarifications landed (#113, #114).

Production changed very little in substance. A filter over the diff for non-comment lines leaves one change: the request-side body tee now goes through the same fail-open guard as the response side, via one shared helper `teeOrPass` with two real users (`./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/CapturingDecorators.kt`). That closes an asymmetry between the two directions, adds no layer, and is no finding. Everything else is KDoc, comment prefixes per ADR-0013, and one visibility change.

The architecture still fits the problem: one shared contract inlined by Shade (ADR-0003), a blocking and a reactive twin, no paradigm mixing, no wrong dependency direction. The previous review's systemic pattern P1 (engine knowledge in three places, the third one unguarded) has largely dissolved. The cancellation page's exception columns are now pinned per engine, and CONTRIBUTING records this as a deliberate exception to its rule of not pinning engine exception types.

One Low finding remains, and it is a residue of that very fix: for Reactor Netty the page and the contract's KDoc promise the direct cause "where §2.2 names it", but the Reactor Netty suite pins only the `IOException` type, not the `InterruptedException` cause that §2.2 states. The tendency of the project is neither over- nor under-engineering in the code. The weight lies in evidence and documentation, and that weight is now guarded.

**Compact test verdict.**

*Testability of the architecture: very good.* Both entry points can be constructed without a Spring context. Time, correlation ID and masker are `fun interface` seams with real host users. The ADR-0015 logic is verifiable with a flag set on the test thread and hand-built exceptions.

*Utilization and pyramid: healthy, unit-heavy, with a top that grew deliberately.*

- 487 `@Test`/`@ParameterizedTest`/`@FuzzTest` annotations, 487 rationale blocks.
- 80 real-socket cases from the two contracts (12 tests × 5 request factories, 5 × 4 connectors), plus the pool scenarios of the Apache and Reactor Netty suites.
- 11 `@SpringBootTest` files, unchanged.
- Test lines to production lines: 2.7 : 1.

*Most significant gaps and anomalies:*

- No untested score-4/5 core; the Shade boundary remains covered by `./consumer-smoke/`.
- The new exception pinning has one incomplete row: Reactor Netty's cause (finding 1).
- `RequestFactoryContract` grew to 599 lines with three helper types (`AbandonedCallExceptions`, `ExpectedThrowable`, `EngineThrowRecorder`). These have 5 real subclasses as users and are not a framework.

## 2. Problem Baseline & Methodology

### Problem baseline

**What the system does.** Unchanged since the morning review: exactly one structured `adapter_*` log line per outgoing HTTP exchange, blocking (`ClientHttpRequestInterceptor`) and reactive (`ExchangeFilterFunction`), plus six meter families in the host's registry. There is no persistence, no messaging and no business domain. The "domain" is the HTTP-client lifecycle: where the event is emitted, cancel semantics, identity, fail-open behaviour.

**Real forces.** Fail-open into the diagnostic channels, twin symmetry as an invariant (`./CONTRIBUTING.md`), lazy cancellable bodies against close-based emission, a single artifact per twin (ADR-0003), and the ADR-0015 cancellation semantics under a `TimeLimiter` (issue #88). New in this short cycle: the maintainer's decision that the operator page on engine behaviour must be guarded by tests (CONTRIBUTING "One deliberate exception", ADR-0015 history entry). That decision is a justifying force for the growth of `RequestFactoryContract`.

**Documented architectural intent.** 15 ADRs, unchanged in number. ADR-0015 gained a history entry for the exception pinning. All ADRs are counted as justifying forces.

**Technology coherence.** Unchanged and clean. Engine knowledge in production is kept by class name without a compile dependency (`Timeouts`, `Cancellations`). Reactor Netty's pool acquire timeout, newly pinned in `./legatium-webclient-logging/src/test/kotlin/eu/inqudium/legatium/webclient/logging/ReactorNettyConnectorIntegrationTest.kt`, is a JDK `TimeoutException` and needed no new name.

### Test topology and testability as a baseline signal

- **Volume:** 78 test source files with 16,142 lines (including `consumer-smoke`).
- **Fakes:** no mocking library; hand-written fakes and the common module's test-jar fixtures.
- **Boot context:** `@SpringBootTest` in 11 files.
- **Real-engine top:** `RequestFactoryContract` (12 tests × 5 subclasses, three of them new: the abandoned-call exception scenarios) and `ConnectorContract` (5 × 4); the HttpClient 4 adapter and its 11 cases were removed (finding 1 of the morning review).

The shape is unchanged in kind: classification, ordering and flag handling are proven without an engine. Only what an engine itself does goes to the real-socket top, and it cannot be proven any other way.

### Methodology

The full ranking from the morning review was re-validated against the diff since `7deba240bd1eba4e707cdcd2514589e322aef7f4`:

- production +655/−546 lines across 34 files, of which a non-comment filter leaves the `teeOrPass` change, the request-tee wiring in `ClientRequestLoggingFilter`, and one `private` → `internal`;
- tests +538/−394 (exception pinning, Reactor Netty pool acquire, request-tee failure tests; HttpClient 4 removed);
- documentation +121/−49 (cancellation page, CONTRIBUTING, ADR-0015, CHANGELOG).

Units with substantive change were re-read in full (`RequestFactoryContract` and its five subclasses, `CapturingDecorators.kt`, the request-tee path of `ClientRequestLoggingFilter.kt`, the cancellation page). The unchanged units keep their scores and verdicts of the morning review after a spot check.

The claims of `./docs/cancellation-by-engine.md` §2.2 were compared row by row with each subclass's `abandonedCallExceptions`. For Reactor Netty the cause was checked against the Spring sources: `ReactorClientHttpRequest.convertException` builds `new IOException(ex.getMessage(), cause)` with the `InterruptedException` from `block()` as `cause`, so the documented cause is real and directly pinnable.

Both findings of the morning review were verified as fixed at this commit:

- `HttpClient4RequestFactory*` no longer exists; `TimeoutsTest` still tests the HttpClient 4 names.
- `abandonedCallExceptions` exists in all five subclasses.

Checked and rejected as findings:

- **`teeOrPass` as a top-level function:** two real users (request decorator, response body); it removes a duplicated try/catch instead of adding indirection.
- **`EngineThrowRecorder`, `ExpectedThrowable`, `AbandonedCallExceptions` in the contract:** three small helper types justified by the CONTRIBUTING decision and used by 5 subclasses; a plainer form (asserting in each subclass) would duplicate the scenarios five times.
- **The page's assertion that no engine reports an interrupt as a plain `InterruptedIOException` (§3):** consistent with every row of §2.2 and with the flag assertions of the contract (`interruptedAfter`, pinned for every engine).

Blind spots: no runtime measurement, no consumer survey, no build run for this review.

## 3. Statistics

| Severity | Count |
|---|---:|
| Critical | 0 |
| High | 0 |
| Medium | 0 |
| Low | 1 |
| **Total** | **1** |
| **Systemic patterns** | **0** |

| Scope | Count |
|---|---:|
| Kotlin production files | 43 |
| Production lines (incl. comments/blank lines) | 6,048 |
| of which comment lines | ~2,686 (44 %) |
| Test sources (Kotlin + Java, incl. `consumer-smoke`) | 78 |
| Test lines | 16,142 |
| `@Test`/`@ParameterizedTest`/`@FuzzTest` annotations | 487 |
| Rationale blocks | 487 |
| `@SpringBootTest` files | 11 |
| Real-engine test cases (contracts × engines) | 80 |
| ADRs | 15 |

## 4. Ranking Table

| Unit | Score | Rationale |
|---|---:|---|
| `./legatium-webclient-logging/src/main/kotlin/eu/inqudium/legatium/webclient/logging/{ClientRequestLoggingFilter,ObservedResponse,ObservedBody,Exchange,BoundedBodyCapture,CapturingDecorators}.kt` | 5 | Reactive hot path with its own operators; **changed:** request tee now fail-open through the shared `teeOrPass`, symmetric with the response side; load-bearing, no finding |
| `./legatium-restclient-logging/src/main/kotlin/eu/inqudium/legatium/restclient/logging/{ClientRequestLoggingInterceptor,Exchange,ExchangeLogEmitter,Cancellations}.kt` with `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/{CauseGraph,Timeouts}.kt` | 5 | ADR-0015 mechanism; comment-only changes since the morning; no finding |
| Engine knowledge across tests and documentation: `./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/RequestFactoryContract.kt` and its 5 subclasses, `./docs/cancellation-by-engine.md` | 4 | **Changed:** exception pinning per engine (fix of the morning's finding 2); one row incomplete (finding 1) |
| `./legatium-common/src/main/kotlin/eu/inqudium/legatium/common/ClientLoggingMetrics.kt` | 4 | 559 lines, ADR-0008; comment-only changes; no finding |
| Build and distribution boundary: `./pom.xml`, module POMs, Shade, test-jar, `./consumer-smoke/` | 3 | HttpClient 4 test dependency left only in `legatium-common` with a named user; no finding |
| Both `ClientLoggingAutoConfiguration.kt`, `ClientLoggingPropertyOrigins.kt`, `ClientObservationWiring.kt` | 3 | ADR-0014 holds; comment-only changes; no finding |
| Test architecture: test-jar fixtures, contracts, rationale convention | 3 | Contract helpers with 5 real users; no framework; no finding |
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

- [x] 1. [`./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/ReactorNettyRequestFactoryIntegrationTest.kt:24-28`; against `./docs/cancellation-by-engine.md:19-20`, `:75` (§2.2, Reactor Netty row), `:270-273` (§7) and `./legatium-restclient-logging/src/test/kotlin/eu/inqudium/legatium/restclient/logging/RequestFactoryContract.kt:46-50`] {Low} {Confidence: high} {Consistency}
  - The exception pinning leaves out exactly the cause the page names for Reactor Netty.
    - Actual structure: §2.2 says Reactor Netty throws "`IOException` over the `InterruptedException` from `block()`". §7 says the suites pin "the exact type at the interceptor (with the message prefix and the direct cause where §2.2 names them)", and the introduction says "everything on this page about the engines Spring ships is pinned". The JDK and Jetty subclasses pin type, prefix and the `InterruptedException` cause; the Reactor Netty subclass pins only `ExpectedThrowable(IOException::class.java)`.
    - Solved problem / justifying force: none visible for the omission. The cause is deterministic: Spring 7.0.9's `ReactorClientHttpRequest.convertException` wraps the `InterruptedException` of `block()` directly as the `IOException`'s cause. No comment or commit message explains why this one row stops at the type.
    - Cost: small. The cause is the only part of this row that tells an operator that the `IOException` means an interrupt and not a transport failure, and it is also what legatium's cause-chain fallback (`Cancellations`) relies on when the flag was consumed. A Reactor Netty or Spring upgrade that wraps differently would leave the page wrong while the page claims the opposite. Classification stays safe either way, because the flag is pinned separately.
    - Simpler alternative: add the cause `InterruptedException` to the Reactor Netty expectation, as in the JDK and Jetty subclasses; if it should deliberately stay unpinned, say so in that row of §2.2 and drop "everything" from the introduction.
    - Reversibility: one test line or one documentation sentence, trivial.
  - **Fixed (2026-10-23) - pinned, the page stays as it is:** the Reactor Netty expectation now names the direct cause, `ExpectedThrowable(IOException::class.java, cause = InterruptedException::class.java)`, like the JDK and Jetty subclasses. `ReactorNettyRequestFactoryIntegrationTest` is green (12 tests); with a deliberately wrong cause (`SocketException`) exactly its three exception scenarios fail. No documentation change was needed: §2.2, §7 and the introduction were already right once the row is pinned.

## 6. Systemic Patterns

No systemic patterns at this commit.

Pattern P1 of the morning review (engine knowledge in production, tests and documentation, with the documentation unguarded) was rechecked. Production holds 7 timeout names and 4 cancellation markers, unchanged; the tests now pin the documentation's exception columns for all 5 Spring-shipped engines; the only unguarded parts are explicitly marked as such on the page (HttpClient 4 as a dated observation, the Apache retry counts). The three places still exist, but the cost the pattern named, silent drift of the operator page, is now covered by the build. Finding 1 is a single gap in that coverage, not a recurring mismatch.

Checked and not counted as patterns:

- **Comment share of 44 %:** a decided convention (ADR-0013), controlled by its own audit process (applied again on 2026-10-23).
- **Rationale prose (487 blocks):** an ecosystem convention.
- **The deliberately duplicated twin files:** below the 90 % threshold, consistent with ADR-0003.

---

## 7. Resolution (2026-10-23, branch `fix/architecture-review-2026-10-23-evening`)

- **1:** fixed (see the finding). Verified by `mvn clean verify` (619 tests, 0 failures, ktlint green).

---

*No code was changed by this analysis. The only write operation is this report; the "Simpler alternative" describes a strategy to be applied or declined in a separate session.*
