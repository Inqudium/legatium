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
  `http-adapter-exchange`, namespace `adapter-logging.*`. Code names keep
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
  configuration reference, and the lockstep tests binding both to both twins.
- Documentation site (MkDocs Material), test-evidence and coverage pages,
  Dokka API references; CI with SBOM/OSV scan, CodeQL, OpenSSF Scorecard,
  nightly Jazzer fuzzing, SLSA-attested releases - the Inqudium project setup.

[Unreleased]: https://github.com/Inqudium/legatium/commits/main
