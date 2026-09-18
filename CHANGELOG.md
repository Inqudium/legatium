# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Both twins: a **wiring report** at DEBUG on the auto-configuration's own logger
  (`eu.inqudium.legatium.<twin>.logging.ClientLoggingAutoConfiguration`), so a host can read from
  its log whether the library is switched on and actually configured a client: one line when the
  auto-configuration is active, one when the interceptor resp. filter bean is registered (with the
  bound properties, masking key redacted), one per customizer registered, and one per
  `RestClient.Builder`, `RestTemplate` or `WebClient.Builder` the customizer attached the module to,
  with the number of earlier interceptors or filters in front of it. At TRACE the bean line is
  followed by the **origin** of every `adapter-logging.*` value Boot bound - file and line,
  environment variable, property source - and by every value of the same name a lower-precedence
  source also holds, marked as shadowed; the masking key is redacted, unset keys are not listed
  (`ClientLoggingPropertyOrigins` in `legatium-common`, one rendering for both twins). Nothing is
  logged with `adapter-logging.enabled=false`. The auto-configuration tests pin the lines and the
  silence, the common module's test the rendering.

### Changed

- Both twins: the message of a **named** client's arrival line and completion event names the call
  by the client's name in place of the request target
  (`Adapter http exchange POST billing -> 200 [...]`), because behind an egress sidecar the target
  is the same for every dependency and a plain-text appender shows nothing else. An unnamed client's
  message keeps the target; the target of a named one stays on the `adapter_route` MDC entry and the
  `adapter_url_*` fields. `TwinContractTest` pins both forms; ADR-0009's message section is amended.

## [1.1.0] - 2026-09-17

### Added

- `benchmarks/`: a standalone JMH module (no reactor child) measuring the shared body buffer
  against `ByteArrayOutputStream` and Spring's `FastByteArrayOutputStream` on the write and the
  render path, with a CI job that compiles it against the installed `legatium-common`. Results and
  their reading: `docs/assessment/BENCH_REPORT-2026-09-17T17-07-53.md` (bulk writes within 10 % of
  the streams, byte-wise writes 6-11x faster than the JDK stream, complete rendering equal to the
  streams' `toString` to the byte) and `docs/assessment/PERF_ASSESSMENT-2026-09-17T17-49-11.md`
  (the buffer at 500 client calls per second, code effort against the alternatives, memory peaks
  and GC pressure per cap).
- `adapter_name`: the logical name of the client that made the call, for the hosts whose
  dependencies all sit behind one egress sidecar or proxy and therefore share one
  `adapter_url_host`. The host names a client once, on its builder, through the request
  attribute `ClientRequestLoggingInterceptor.ADAPTER_NAME_ATTRIBUTE` resp.
  `ClientRequestLoggingFilter.ADAPTER_NAME_ATTRIBUTE` (one string,
  `eu.inqudium.legatium.adapterName`, on both twins); every call of that client then carries
  the field on the completion event and the arrival line, and the three body meters tag it as
  `name` (`UNNAMED` for a client nobody named). The component template maps the field, the
  lockstep test pins it; why an attribute and not a header, a property or a path rule is
  [ADR-0009](docs/adr/ADR-0009-adapter-name-is-a-request-attribute.md).
- WebClient twin: the caller's context is restored around the exchange line. The filter captures
  the Reactor Context the caller subscribed with and the emitter turns it back into thread-locals
  for the single log statement, through the `ThreadLocalAccessor`s the host registered with
  Micrometer's context propagation - so a client line completed on an event-loop thread carries
  the inbound request's `endpoint_*` identity (Limesium) under Boot's default
  `spring.reactor.context-propagation=limited`, and identically across retry attempts. Additive
  like the emission scope (`clearMissing` off), trace keys still owned by the module. Opt-in by
  `io.micrometer:context-propagation` on the classpath (a new optional dependency), no
  configuration key; a host without it behaves as before. The RestClient twin needs no counterpart,
  its wire call blocks the caller's thread; why the Reactor Context and not a thread-local snapshot
  is [ADR-0010](docs/adr/ADR-0010-reactive-twin-restores-the-callers-context.md).
- RestClient twin: the caller's MDC is snapshotted at wiring, on the calling thread, and
  restored around the exchange line when the host closes the response on **another** thread
  (a streamed body handed to a pooled reader) - the one case in which the blocking twin's client
  line could not join the server line. Own and trace keys are left out of the snapshot, it is
  not applied on the calling thread (the live MDC is the truth there), and it is additive like
  the emission scope. One map copy per call, nothing for an empty MDC, no configuration key;
  [ADR-0011](docs/adr/ADR-0011-blocking-twin-snapshots-the-callers-mdc.md).

### Changed

- Both twins: the body buffer beneath the two `BoundedBodyCapture`s is one class in
  `legatium-common`, `BoundedByteBuffer` (ADR-0003) - a cap-bounded bare array instead of a
  `ByteArrayOutputStream` per twin, allocated on the first buffered byte, sized once by the declared
  `Content-Length` (the WebClient twin hands over the request's at write time and the response's at
  handover, the RestClient twin's `expectBytes` sizes it too), doubled from there up to the cap, cut
  back for the RestClient tee's `reset`. The truncated rendering decodes the prefix and writes the
  truncation note into the same buffer, materialized once instead of three copies of the prefix.
- Both twins, the shared body buffer's memory: the truncated rendering no longer allocates a
  `CharBuffer` of `size * maxCharsPerByte` chars - it decodes once through a 1024-char scratch into a
  builder sized by the prefix plus the note, and UTF-8, the charset of nearly every logged body,
  skips the decoder loop through a bounded tail check and the JDK's own `String` decoding; rendering
  a full cap of N ASCII bytes drops from about 4N to 3N of transient memory and the truncated ASCII
  rendering to the time of the naive concatenation. Without a declared length the first array has
  256 bytes instead of the first write's length (a byte-wise reader no longer allocates and copies
  through 1, 2, 4, ... 128), and a declared length allocates at most 64 KiB in one go - a body
  declared huge cannot reserve a large cap with its first byte (caps up to 64 KiB are sized exactly
  by their declaration as before). Hardening in the same change: the ranged write checks its source
  range before clipping and before allocating, the doubling runs in `Long` so growth past 1 GiB
  keeps doubling, and a rendering total below the buffered size is rejected instead of rendered as a
  complete body.
- WebClient guide §4.7 documents that Reactor Netty's one-time retry on a stale pooled connection
  cannot run the request tee twice: the retry fires only while no headers were sent, the tee runs
  when the body emits inside `writeWith`, never before the headers go out. Probed against Reactor
  Netty 1.3.7 (`docs/assessment/RETRY_PROBE-2026-09-17T19-11-37.md`).
- Documented that "inside the filters/interceptors of earlier customizers" means customizers
  ordered before `Ordered.LOWEST_PRECEDENCE - 10`: a host customizer without an `@Order` is applied
  after the module's, and its filter or interceptor runs inside the logging. Pinned by a test in
  both auto-configurations. The WebClient guide also names the limit of the cancel heuristic (a
  `publishOn` between the body and an early-exiting consumer logs `cancelled`) and that the duration
  includes the consumer's synchronous terminal work.
- The shared guide (`docs/GUIDE.md`) is now the **Common guide** (site nav, READMEs, module
  guides), and it absorbs what the two module guides had each repeated: the exchange line and
  its structured document, the injectable collaborators, the masking fingerprint, tracing
  making every call traced, the fail-open promise, what the modules deliberately do not do,
  and the shared code in `legatium-common` with its class table and the twin contract's
  lockstep tests. The module guides keep architecture, wiring and stack specifics and point
  to the Common guide for the rest.
- Common guide §3 explains why the `@ConditionalOnMissingBean` back-off of the collaborator
  beans is reliable (auto-configuration import order) and where it ends (a host bean that
  itself lives in another auto-configuration); the auto-configuration tests of both twins
  now pin the back-off for `NanoTimeSource` and `CorrelationIdGenerator` as well.
- Reproducible builds: the root POM sets `project.build.outputTimestamp`
  (bumped in every release commit), the jar and sources manifests no longer
  carry `Created-By`/`Build-Jdk-Spec` (the build-JDK line was the one thing
  that kept a JDK 25 CI build and a JDK 26 local build of the same tag from
  matching), and the javadoc jar is rendered by Dokka but packaged by the jar
  plugin, because Dokka's `javadocJar` goal writes the build time and JDK into
  the archive. Verified: three builds of one commit - twice on JDK 26, once on
  Temurin 25 - produce byte-identical main (shaded), sources and javadoc jars
  for both twins. From the next release on, the jars attached to the GitHub
  release (SLSA-attested) and the jars deployed to Maven Central are therefore
  the same bytes; README ("Reproducible builds") and SECURITY.md describe it.

### Fixed

- WebClient twin: the body tee counts a chunk in full before it copies the prefix the capture keeps.
  A `DataBuffer` whose non-advancing read throws now costs the logged text of that chunk, no longer
  its bytes in the size sample as well; the event carries the truncation note for the bytes that
  flowed instead of no body field.
- RestClient twin: an answer that carries no body by the protocol (a 1xx, 204 or 304, a
  `Content-Length: 0`) is counted `complete` on `adapter.response.body.read` at handover. Spring's
  clients never open such a body, so every 204 counted `unread` before - a route of deletes and
  updates read as 100 % discarded payload on the counter that exists to show exactly that, and the
  same answer counted `complete` on the reactive twin. The state tag's observation points are now
  documented per stack in the common guide's §7.4 and on `BodyReadState`, including the reactive
  `releaseBody()` drain (`toBodilessEntity()` counts `complete` there).
- Both twins: the optional arrival line (`log-request-start`) runs inside the `Throwable` boundary
  of the entry point. A logging backend that died with an `Error` while the line was written left
  the exchange open on `adapter.logging.exchanges.open` for the life of the process and, on the
  RestClient twin, the call scope's `adapter_*` keys on the calling thread.
- Both twins: a scope teardown that fails after the line is on the logger (a throwing MDC adapter or
  host accessor on the way out) is counted `stage=wiring`, no longer as a lost line under
  `stage=arrival` resp. `stage=emission` - the reconciliation of `adapter.logging.events` against
  the fail-open counter holds again.
- Both twins: a peer whose authority `java.net.URI` refuses to parse as a host - a service name with
  an underscore such as `billing_api`, common in Compose and Kubernetes - is named as written in
  `adapter_url_host`, the route and the message (they rendered `http:///path` and no host before),
  and `exclude-hosts` matches it; an IPv6 literal in `exclude-hosts` matches with or without its
  brackets.
- `adapter.logging.failopen{stage=wiring}` for a host meter that throws on every update is still
  counted per failure, but warned once per meter name instead of twice per exchange.
- RestClient twin: the wiring runs its side-effect-free steps (coordinates, captures, time source,
  caller MDC) before the correlation header is stamped and the origin counted, so a host time source
  or MDC adapter that throws degrades to an unlogged pass-through with nothing on the wire and
  nothing counted.
- Consumer smoke test: the WebClient line is awaited instead of read right after `block()` - the
  reactive twin emits after it handed the body's completion on, so the read raced the emission on a
  slow runner. CI derives the consumer's `legatium.version` from the root POM instead of trusting
  the hand-pinned copy.
- The published POMs name the repository itself as homepage and SCM. Maven appends the
  module name to an inherited `url` and `scm`, so Maven Central showed the 1.0.0 twins
  with a homepage `.../legatium/legatium-restclient-logging` that does not exist; the
  root POM now switches that inheritance off and each twin states its `url` explicitly.

## [1.0.0] - 2026-09-05

### Added

- Initial release of Legatium: one structured `adapter_*` log line per outbound
  HTTP exchange - the outbound twin of [Limesium](https://github.com/Inqudium/limesium),
  built to the same design (fail-open, one exactly-once emission, level/outcome
  decoupling, header allowlist with stable masking, passive bounded body tee,
  six meters) with the `adapter_*` field family and the `adapter-logging.*`
  namespace.
- `legatium-restclient-logging` - auto-configured `ClientHttpRequestInterceptor`
  attached to every `RestClient` and `RestTemplate` Boot builds; the event is
  emitted when the response is closed, so status, headers, body and duration are
  final; outcomes `success` / `failure` / `timeout`.
- `legatium-webclient-logging` - auto-configured `ExchangeFilterFunction`
  attached to every `WebClient` Boot builds, field- and configuration-identical
  twin of the RestClient module; the event is emitted at the response body's
  terminal signal; outcomes `success` / `failure` / `timeout` / `cancelled`.
- Identity per [ADR-0002](docs/adr/ADR-0002-trace-id-is-the-request-id.md): the
  outgoing `traceparent`'s trace id is the request id and the call goes out
  untouched; a traceless call gets a generated `X-Correlation-Id` sent along.
  Counting correlation id generator ([ADR-0004](docs/adr/ADR-0004-counting-correlation-id-default.md)).
- Header values are masked by default
  ([ADR-0005](docs/adr/ADR-0005-headers-masked-by-default.md)): `masked`
  defaults to `["*"]`, and the new `unmasked` list names the headers that may
  appear in plaintext (no wildcard) - so `includes: ["*"]` costs readability,
  not confidentiality. Emptying `masked` switches masking off, visibly.
- Injectable `HeaderValueMasker`: the rendering of masked header values is a
  `@ConditionalOnMissingBean` bean shared by both twins - the built-in default is
  the stable `length:hash` fingerprint, a host pins a keyed or fixed masker
  instead; the properties decide which values are masked, the bean decides how.
  `adapter-logging.masking-key` keys the built-in fingerprint (HMAC-SHA256) without
  a bean: same shape and stability, guess-proof without the key.
- Body logging is a mode per direction, not a switch
  ([ADR-0006](docs/adr/ADR-0006-bodies-logged-by-outcome.md)):
  `log-request-body` / `log-response-body` take `never` (the default),
  `on-failure` or `always`. `on-failure` writes a body only when
  `adapter_outcome` is not `success` or the status is a 4xx - the response side
  decides at emission,
  the request body is captured before the outcome is known and discarded on
  success - which keeps body logging affordable outside a debug session.
- The operator-facing vocabulary is `adapter`, the counterpart of limesium's
  `endpoint` ([ADR-0007](docs/adr/ADR-0007-adapter-is-the-operator-vocabulary.md)):
  fields `adapter_*`, MDC keys `adapter_request_id` / `adapter_method` /
  `adapter_route`, meters `adapter.logging.*` and `adapter.*.body.*`, logger
  `adapter-http-exchange`, namespace `adapter-logging.*`. Code names keep
  their `Client*` form. Chosen over `client` (ECS names the remote party so),
  `upstream` (hop-relative) and `dependency` (Maven).
- Architecture review of 2026-09-04, applied: the metrics owner
  (`ClientLoggingMetrics`, parameterised by `ClientStack`) and the activation
  (`ClientActivation`) are shared in `legatium-common` - the twin copies had
  converged to 95 % / 96 % identity; ADR-0003 names the 90 % threshold and
  decides the inlining stays through 1.0. The documentation follows the
  content cut: one shared [Legatium guide](docs/GUIDE.md) for integration,
  configuration, fields and meters, the module guides keep architecture,
  wiring and stack specifics. The by-name timeout tests use the real Netty
  classes (test-scoped) instead of a hand-assembled class file; the
  RestClient twin has one exactly-once guard; the field enum declares its
  wire shape for the lockstep test instead of gating at emission; the id
  generator's narrative moved into ADR-0004.
- Code-style audit of 2026-09-04, applied: the outcome, fail-open stage and
  request-id source vocabularies are enums (`ClientOutcome`, `FailOpenStage`,
  `RequestIdSource`) instead of string constants; the identity resolution of
  ADR-0002 and the URI split live in `legatium-common` (`ClientIdentity`,
  `RequestTarget`) and both emitters classify through one shared
  `Classification`; the entry points and emitters are split into named
  steps; `HeaderValueMasker.DEFAULT`, `NanoTimeSource.SYSTEM` and
  `CorrelationIdGenerator.DEFAULT` are `@JvmField`, the masker factories
  `@JvmStatic`; the RestClient twin's `BoundedBodyCapture` is internal like
  its twin; every test carries the rationale block the contributing guide
  asks for.
- Code and defect analysis of 2026-09-04 (second, at commit `2ed0ba9`), applied:
  the public four-argument constructors of both entry points default their
  masker to `HeaderValueMasker.forKey(properties.maskingKey)`, so a manually
  wired interceptor or filter honours a configured `masking-key` exactly like
  the auto-configured one (previously the unkeyed fingerprint, silently); the
  RestClient twin's request-body contracts are decoupled - the field shows the
  serialized body the client handed to the wire call (evidence, also for a
  refused connection), the size meter records a sample only once a response
  proves the request went out; a response body read exactly to a trustworthy
  declared `Content-Length` counts as `complete` (Spring's
  `ByteArrayHttpMessageConverter` never asks for the EOF - every `byte[]`
  answer was `partial`; a non-numeric `Content-Length` is folded to "unknown"
  without a fail-open count); every operation on the wrapped response that can fail
  the caller - status, headers, `available`, body-stream close, response close -
  marks the exchange failed before it propagates (a throwing close no longer
  emits `success` immediately before the caller's exception); the WebClient
  twin's response `Mono` runs through its own operator (`ObservedResponse`) with
  a `DELIVERING` state around the downstream's `onNext`, so a cancel from
  another thread during the handover completes the exchange as `cancelled`
  instead of leaving it open forever with the gauge one too high; the
  open-exchanges gauge detects a same-type gauge already registered under its
  exact id (a host gauge, another library copy) and goes to the private
  registry with a warning instead of counting invisibly. Pinned by tests at
  each seam: keyed properties through the four-argument constructors (both
  twins), the real `ByteArrayHttpMessageConverter` on an engine-like stream and
  a real JDK-engine `byte[]` call, a refused POST with and without a response,
  a status that cannot be read and a throwing close, a barrier-driven
  concurrent cancel during delivery, an identical host gauge (both twins).
- Reactive body consumption vs. abandonment: a consumer that stops reading the
  body from within its own delivery - Spring's body skip for
  `bodyToMono(Void.class)` / `toEntity(Void.class)` / an unsupported media
  type, a `take(n)` - completes the exchange as `success` with the body
  partially read; `cancelled` is reserved for a subscription the caller
  abandoned (a timeout operator's timer, a disposed caller, a disconnect).
  Previously every fire-and-forget call logged `cancelled` at WARN and, in
  `on-failure` body mode, wrote both bodies of the healthy call. The filter
  wires per subscription (a resubscribing outer retry logs one line per
  attempt), an empty connector completion is a `failure`, and a cancel of the
  response `Mono` after delivery no longer ends the exchange.
- Both twins in one registry: the open-exchanges gauge carries a `client` tag
  (`restclient` / `webclient`), so the second twin's gauge is no longer
  silently dropped by Micrometer's id deduplication.
- RestClient twin: a body that cannot be OPENED, or a read that throws an
  unchecked exception, is a `failure` (previously `success`); status and
  headers are snapshotted at handover; an `Error` from the wire call closes
  the gauge without an emission (the `Exception`/`Throwable` boundary is now a
  documented decision); the origin counter keeps calling a re-entered
  generated id `generated`.
- Body-meter cardinality: the `uri` tag keeps a recorded template only when it
  carries a placeholder (`uri("/things/" + id)` folds to `UNKNOWN`); the
  `host` tag is documented as caller-controlled. Zero-copy file uploads keep
  their `sendfile` path under request-body capture (counted, not copied).
- Correlation header acceptance rule (ADR-0002 amendment): a propagated id is
  adopted only within 200 visible-ASCII characters; anything else is replaced
  by a generated id. Timeouts carried as suppressed exceptions of a composite
  error classify as `timeout`; explicitly listed header names are
  deduplicated; the truncated-body decoder sizes its buffer exactly.
- Test evidence: seed corpora for the three fuzz targets (regression mode now
  replays real inputs), the interrupt-flag restoration of the fail-open guard,
  every binding-time `require`, the previous-value restore and suppressed
  aggregation of `MdcScope`, and the reactive filter under Spring's body skip,
  a resubscription, an out-of-band cancel and an empty completion.
- Engine-agnostic RestClient twin, pinned: one `RequestFactoryContract` runs against
  every request factory Spring ships - the JDK `HttpClient`, Apache HttpComponents 5,
  Jetty, Reactor Netty and `HttpURLConnection` - for the body tee and its read state on
  the engine's stream, the wire correlation header, each engine's real read and
  connect timeout types as `timeout`, a refused connection as the `failure` control,
  and, per engine, whether a gzip answer reaches application and log decoded or as
  sent.
- Connector-agnostic WebClient twin, pinned: the shared timeout classification
  recognises Reactor Netty's connect timeout (`io.netty.channel.ConnectTimeoutException`,
  a `ConnectException` no JDK timeout type covers) by name, next to Netty's
  `TimeoutException` family and the JDK types; per-connector integration suites
  run one contract against Reactor Netty, the JDK `HttpClient`, Jetty and Apache
  HttpComponents 5 - body tees, the wire correlation header, the engine's real
  response and connect timeout types as `timeout`, a refused connection as the
  `failure` control.
- Shared twin core `legatium-common`, inlined by Shade
  ([ADR-0003](docs/adr/ADR-0003-legatium-common-inlined-by-shade.md)), including
  the cross-stack timeout classification and - unlike Limesium - the field enum
  and the `adapter-logging.*` properties class themselves (ADR-0003 amendments of
  2026-09-03: the twins' copies were byte-identical).
- Elasticsearch component template for the `adapter_*` fields, the shared
  configuration reference, and the lockstep tests binding both to both twins;
  the shared literals (meter names, MDC keys, read states, outcome vocabulary)
  pinned once in `legatium-common`, each twin pinning only its message text
  and its own outcome vocabulary.
- Six meter families as a decided scope ([ADR-0008](docs/adr/ADR-0008-six-meters-consumed-not-exported.md)):
  consumed from the host's registry, never exported; a host without a
  `MeterRegistry` gets no-op meters (an empty `CompositeMeterRegistry`) instead
  of a private registry accumulating unread values. The owner's registration
  behaviour is tested once in `legatium-common`.
- Consumer smoke test (`consumer-smoke/`, CI job `consumer-smoke`): the shaded
  twin jars are resolved and started like an application would, with
  `legatium-common` removed from the local repository first - the inlined
  classes, the auto-configuration imports and one exchange line per client
  are verified on the consumer's side of the Shade boundary (ADR-0003
  amendment of 2026-09-05).
- Documentation site (MkDocs Material), test-evidence and coverage pages,
  Dokka API references; CI with SBOM/OSV scan, CodeQL, OpenSSF Scorecard,
  nightly Jazzer fuzzing, SLSA-attested releases - the Inqudium project setup.

[Unreleased]: https://github.com/Inqudium/legatium/compare/1.1.0...HEAD
[1.1.0]: https://github.com/Inqudium/legatium/releases/tag/1.1.0
[1.0.0]: https://github.com/Inqudium/legatium/releases/tag/1.0.0
