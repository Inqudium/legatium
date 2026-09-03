# Security Policy

## Supported Versions

Only the latest released version of Legatium receives security fixes.

## Reporting a Vulnerability

Please **do not** report security vulnerabilities through public GitHub issues.

Instead, report them privately via
[GitHub Security Advisories](https://github.com/Inqudium/legatium/security/advisories/new)
or by email to **legatium@inqudium.eu**.

Please include:

- A description of the vulnerability and its impact
- The affected module and version
- Steps to reproduce, or a proof of concept if available

You will receive an acknowledgement within a few days. Please allow a reasonable
time for a fix to be released before any public disclosure.

## Scope notes

Legatium logs data a service sends to and receives from foreign parties. Reports about
**sensitive data leaking into log output** (headers, bodies, query strings that should be
masked or truncated but are not) are explicitly in scope and very welcome — and so are
reports about the module **altering the outbound call** beyond its documented one
addition (the correlation header on traceless calls).

Measures already in place, so you know what is expected behaviour:

- **Headers are logged by allowlist only.** Nothing is logged unless named in
  `includes`; values in the `masked` list are reduced to a stable
  `length:hash` fingerprint instead of the cleartext value - or to whatever a
  host-provided `HeaderValueMasker` bean renders (a keyed HMAC, a fixed `***`).
- **Bodies are captured passively and bounded.** The tee never buffers,
  replays, or withholds the exchange; `max-body-bytes` caps what can reach
  the log.
- **The wire is touched once, and only when traceless.** A call carrying a
  `traceparent` goes out untouched; a traceless call without a correlation
  header gets one added — nothing else is rewritten, and the header name is
  validated at startup so no engine can reject it per call.
- **Dependencies are scanned continuously.** CI builds a CycloneDX SBOM of
  the resolved graph and fails on any advisory known to OSV — on every change
  and weekly, so newly published advisories surface without a commit.
  Dependabot proposes the version bumps.
- **The code itself is statically analysed.** A CodeQL workflow analyses the
  library sources and the CI workflow definitions on every change and weekly;
  results appear under Security → Code scanning.
- **The boundary parsers are fuzzed.** The components that handle
  peer-controlled or caller-controlled data (body capture, header masking, the
  `traceparent` parser) are Jazzer `@FuzzTest` targets with their invariants
  asserted in the test body: explored nightly by the Fuzz workflow, and
  replayed against the checked-in findings in every build.
- **Release assets carry SLSA build provenance.** The Release workflow
  rebuilds the jars and the SBOM from the release tag, uploads them to the
  GitHub release, and attaches Sigstore-signed SLSA provenance
  (`*.intoto.jsonl`) — verifiable with
  [slsa-verifier](https://github.com/slsa-framework/slsa-verifier). Maven
  Central artifacts are additionally GPG-signed by the release profile.
- **The repository's supply-chain posture is scored publicly.** The OpenSSF
  Scorecard badge in the README links to the current per-check breakdown.
  Read it as a posture indicator, not as a grade: several checks assume a
  multi-maintainer, pull-request-based project and score low by construction
  for a single-maintainer one. Where a deduction is a deliberate trade-off,
  the reason sits next to the decision — see the `repo_token` note in
  `.github/workflows/scorecard.yml`. The **Fuzzing** check in particular can
  read 0 despite the nightly Jazzer fuzzing described above: Scorecard only
  scans for Jazzer targets when Java holds a "prominent" share of the
  repository's bytes, and this Kotlin-dominated codebase sits below that
  threshold — so that score tracks the language ratio, not the actual
  fuzzing coverage. The Fuzz workflow's run history is the authoritative
  signal (ADR-0001).

Things outside this library's control, which the consuming application owns:
what its callers write into MDC and log messages, its TLS configuration, the
peers it chooses to call, and where its log output is shipped and stored.
