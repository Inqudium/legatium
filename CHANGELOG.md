# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Initial release of Legatium: one structured `client_*` log line per outbound
  HTTP exchange - the outbound twin of [Limesium](https://github.com/Inqudium/limesium),
  built to the same design (fail-open, one exactly-once emission, level/outcome
  decoupling, header allowlist with stable masking, passive bounded body tee,
  six meters) with the `client_*` field family and the `client-logging.*`
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
  `client-logging.masking-key` keys the built-in fingerprint (HMAC-SHA256) without
  a bean: same shape and stability, guess-proof without the key.
- Body logging is a mode per direction, not a switch
  ([ADR-0006](docs/adr/ADR-0006-bodies-logged-by-outcome.md)):
  `log-request-body` / `log-response-body` take `never` (the default),
  `on-failure` or `always`. `on-failure` writes a body only when
  `client_outcome` is not `success` or the status is a 4xx - the response side
  decides at emission,
  the request body is captured before the outcome is known and discarded on
  success - which keeps body logging affordable outside a debug session.
- Shared twin core `legatium-common`, inlined by Shade
  ([ADR-0003](docs/adr/ADR-0003-legatium-common-inlined-by-shade.md)), including
  the cross-stack timeout classification and - unlike Limesium - the field enum
  and the `client-logging.*` properties class themselves (ADR-0003 amendments of
  2026-09-03: the twins' copies were byte-identical).
- Elasticsearch component template for the `client_*` fields, the shared
  configuration reference, and the lockstep tests binding both to both twins.
- Documentation site (MkDocs Material), test-evidence and coverage pages,
  Dokka API references; CI with SBOM/OSV scan, CodeQL, OpenSSF Scorecard,
  nightly Jazzer fuzzing, SLSA-attested releases - the Inqudium project setup.

[Unreleased]: https://github.com/Inqudium/legatium/commits/main
