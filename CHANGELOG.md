# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/Inqudium/legatium/commits/main
