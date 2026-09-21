<p align="center">
  <img src="docs/logo/legatium-banner.svg" alt="Legatium — one structured adapter_* line per outbound HTTP exchange" width="640">
</p>

[![Maven Central](https://img.shields.io/maven-central/v/eu.inqudium/legatium.svg?label=Maven%20Central)](https://central.sonatype.com/namespace/eu.inqudium)
[![CI](https://github.com/Inqudium/legatium/actions/workflows/ci.yml/badge.svg)](https://github.com/Inqudium/legatium/actions/workflows/ci.yml)
[![Coverage](https://inqudium.github.io/legatium/coverage/badge.svg)](https://inqudium.github.io/legatium/coverage/)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Last commit](https://img.shields.io/github/last-commit/Inqudium/legatium)](https://github.com/Inqudium/legatium/commits/main)
[![Issues](https://img.shields.io/github/issues/Inqudium/legatium)](https://github.com/Inqudium/legatium/issues)
[![Docs](https://img.shields.io/badge/docs-inqudium.github.io-8E2C21)](https://inqudium.github.io/legatium/)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/Inqudium/legatium/badge)](https://scorecard.dev/viewer/?uri=github.com/Inqudium/legatium)

Legatium logs one structured adapter_* line per outbound HTTP exchange — named after the Roman
legatus, the envoy a service sends to a foreign party, and the record of what came of it. Two
auto-configured Spring Boot twins with identical fields and configuration: a RestClient/RestTemplate
interceptor and a WebClient filter. No starter, no forced transitives.

## What sets it apart

- **One line, when the exchange is truly over.** The event is emitted at response close on the blocking
  stack and at the body's terminal signal on the reactive one, so status, headers, bodies and the
  duration are final: `adapter_duration_ms` is response occupancy including the body read, not a bare
  round trip. A call without a response still yields exactly one line, with `-> -`.
- **Two paradigm twins, one contract.** The `RestClient`/`RestTemplate` interceptor and the `WebClient`
  filter emit the same fields under the same names with the same shapes, bound by the same
  `adapter-logging.*` keys, and lockstep tests pin every literal: a field, a message format or a meter
  that drifts between the twins fails the build.
- **Fail-open, and the loss reports itself.** A logging failure never reaches the caller and never
  changes the call. It is swallowed, counted in `adapter.logging.failopen` by stage, and the events
  counter is the ground truth to reconcile against the log index, so a lost line is visible through a
  channel that does not depend on the line.
- **Identity that joins the lines.** The trace id is the request id; on a traceless call the module sends
  an `X-Correlation-Id` instead, so the peer can quote it. A client line emitted while a request is
  served inherits the server line's identity from the MDC, and the reactive twin restores the caller's
  context around its emission, the blocking twin the caller's MDC for a response closed on another
  thread: the outbound line always carries the identity of the request that caused it.
- **Header values masked by default, bodies teed as they flow.** A logged header value is a stable keyed
  fingerprint unless it is on an explicit plaintext allowlist; the same `masking-key` on both sides of
  the family makes a masked token read identically on the inbound and the outbound line. Bodies are
  never pre-read or replayed: they are teed as the application reads them, bounded by `max-body-bytes`,
  and `on-failure` logs them only for the exchanges that went wrong.
- **An outcome that names who is responsible, meters that are consumed, not exported.** `success`,
  `rejected` (a 4xx), `failure`, `timeout` and `cancelled` say which side the disposition belongs to;
  the level carries severity separately. Six meter families are fed into the host's own registry,
  pre-registered at zero so a `rate()` alert sees the baseline before the first occurrence; rates,
  latencies and status distributions are left to `http.client.requests` on purpose.
- **The logger level is the volume control, at runtime.** Because the level carries severity only, the
  level of the `adapter-http-exchange` logger decides how much is logged without changing what a line
  means: `INFO` every call, `WARN` failures, timeouts, slow calls, cancellations and the four escalated
  rejections, `ERROR` only calls that threw, `OFF` nothing. Level and outcome are resolved before the
  event is built, so a disabled level costs no assembly, no header selection, no body decoding, and the
  meters are recorded before the gate. Turn it up during an incident through the host's logging backend
  (Boot's loggers endpoint included) and down again, no restart, no redeploy; the module's own logger
  under `eu.inqudium.legatium` reports at `DEBUG` how it is wired and at `TRACE` where every property
  value came from.

## About the name

Legatium derives from *legatus*, the Roman envoy. A client call is exactly
that: the service sends someone to a foreign party and records what came of
it. Limesium guards the border from within; Legatium accompanies the envoy
outward. The pair of names explains itself in a single sentence, sounds like
an element, and is entirely unclaimed on GitHub.

The form follows the naming of chemical elements, like its sibling
[**Limesium**](https://github.com/Inqudium/limesium) — the project that logs the *inbound*
crossings at the service's own frontier. Together they cover both directions of a service's HTTP
traffic with the same design: one structured line per exchange, fail-open, identical across two
paradigm twins. Legatium's fields carry the `adapter_` prefix and Limesium's the `endpoint_` prefix,
so a log document may hold both — a client line emitted while a request is being served inherits
the server line's identity from the MDC — and no field ever means two things. Both are published
under the `eu.inqudium` group, the fictional periodic table of **Inqudium**.

Two paradigm twins with identical fields and identical configuration:

| Module | Client | Root package |
|---|---|---|
| [`legatium-restclient-logging`](legatium-restclient-logging/README.md) | `RestClient` and `RestTemplate` (blocking, `ClientHttpRequestInterceptor`) | `eu.inqudium.legatium.restclient.logging` |
| [`legatium-webclient-logging`](legatium-webclient-logging/README.md) | `WebClient` (reactive, `ExchangeFilterFunction`) | `eu.inqudium.legatium.webclient.logging` |

Both are auto-configured Spring Boot libraries — no starter, no forced logging transitives; the host
application brings the client and its engine (the JDK `HttpClient`, Apache, Reactor Netty, ...) and the
Logback binding. Neither needs a web application: a batch job or a message consumer that calls out is
a client too.

## What one line says

```
Adapter http exchange POST https://api.example.com/things/42 -> 200 [adapter_request_id=4bf92f3577b34da6a3ce929d0e0e4736 traceId=4bf92f3577b34da6a3ce929d0e0e4736 spanId=00f067aa0ba902b7]
```

plus the structured `adapter_*` key-values — outcome, duration until the response was fully read, method,
status, peer host, the client's name when the host set one, URI template, path, query, optional headers
and bodies — and the identity in the MDC. A client the host named reads by that name in place of the
target (`Adapter http exchange POST things -> 200 [...]`); the target stays in the MDC and the fields.
The trace ids come from the `traceparent` header the host's tracing propagation put on the request; on a
traceless call the module sends an `X-Correlation-Id` instead, so the peer can quote it. Outcomes:
`success`, `rejected` (a 4xx), `failure`, `timeout`, and on the reactive stack `cancelled`.

## Documentation

**Documentation site:** [inqudium.github.io/legatium](https://inqudium.github.io/legatium/) —
guides, Elasticsearch mapping, generated [test evidence](https://inqudium.github.io/legatium/tests/test-evidence/),
[coverage reports](https://inqudium.github.io/legatium/coverage/), and the Dokka
[API](https://inqudium.github.io/legatium/api/legatium-restclient-logging/)
[references](https://inqudium.github.io/legatium/api/legatium-webclient-logging/).

- [Common guide](docs/GUIDE.md) — everything that is one contract for both twins, written once:
  prerequisites, dependency, overriding beans, the exchange line and the logging backend, index mapping,
  configuration, fields, MDC keys, meters, trace correlation, scope and fail-open guarantees, the shared
  code.
- [RestClient guide](legatium-restclient-logging/docs/GUIDE.md) — the long-form guide of the
  reference implementation: architecture, integration, configuration, metrics.
- [WebClient guide](legatium-webclient-logging/docs/GUIDE.md) — the twin's guide, including
  the deliberate stack differences.
- [Configuration reference](docs/adapter-logging-reference.yml) —
  every `adapter-logging.*` key with its default, contract-tested against both twins.
- [Elasticsearch mapping](docs/elk/README.md) — the ready-made
  component template for the `adapter_*` fields.
- [Decision records](docs/adr/) — why the trace id is the request id, why the shared code is
  inlined, why the default id counts instead of rolling dice.
- [**Limesium**](https://github.com/Inqudium/limesium) — the sibling project for the *inbound*
  side: one structured `endpoint_*` line per request the service receives, on the logger
  `endpoint-http-exchange`, built to the same design. Run both and a log document holds the
  server line and the client lines of the calls it made, joined by the shared request id - and
  because both mask header values with the same stable fingerprint (the same `masking-key` on
  both sides keeps it so), a masked token reads identically on the inbound and the outbound line.


### Quick start

Each Legatium release is built and tested against one Spring Boot line, one Kotlin line and one Java
target; the table is the history of those lines, newest first. The Java column is the bytecode target the
artifacts run on - the build itself needs JDK 24+.

| Legatium | Spring Boot | Kotlin | Java |
|---|---|---|---|
| 1.2.0 | 4.1.x | 2.4.x | 21 |
| 1.1.0 | 4.1.x | 2.4.x | 21 |
| 1.0.0 | 4.1.x | 2.4.x | 21 |

Pick the module for the client the host calls out with and follow the **Usage** section of its README —
prerequisites, the dependency with the current version, how the interceptor or filter is wired
automatically, when and how to wire it by hand, and what one logged exchange looks like as text and as
JSON:

- **`RestClient` / `RestTemplate`** (blocking):
  [`legatium-restclient-logging` → Usage](legatium-restclient-logging/README.md#usage) —
  [automatic wiring](legatium-restclient-logging/README.md#automatic-wiring),
  [manual wiring](legatium-restclient-logging/README.md#manual-wiring).
- **`WebClient`** (reactive, also from coroutines):
  [`legatium-webclient-logging` → Usage](legatium-webclient-logging/README.md#usage) —
  [automatic wiring](legatium-webclient-logging/README.md#automatic-wiring),
  [manual wiring](legatium-webclient-logging/README.md#manual-wiring).

An application may carry both jars — a servlet host using `RestClient` for most calls and `WebClient`
for a streaming one gets both logged, in one format.

## Build

```
mvn verify
```

Maven multi-module build (group `eu.inqudium`), Java 21, Kotlin, Spring Boot parent. The twins compile
against the shared `legatium-common` module through the reactor, so build from the root (or with `-am`).

### Reproducible builds

The same source and version produce the same bytes on any machine: the root
POM sets `project.build.outputTimestamp` (bumped in every release commit), so
the jar, source and shade archivers write that instant as every zip entry's
timestamp, sort the entries and normalize their permissions, and the manifests
carry no build user, build JDK or Maven version (`Build-Jdk-Spec` is omitted on
purpose - CI builds on JDK 25, a maintainer on whatever 24+ is installed). This
holds for all published jars of both twins: the javadoc jar is rendered by
Dokka but packaged by the jar plugin, because Dokka's own `javadocJar` goal
writes the build time and JDK into the archive. Consequently the jars the
Release workflow attaches to the GitHub release (built on the tag by GitHub
Actions, attested with SLSA provenance) and the jars deployed to Maven Central
from a local checkout of the same tag are byte-identical. To check a build, run
`mvn -DskipTests package` twice, or once on the tag, and compare
`sha256sum <twin>/target/<twin>-<version>.jar` with the release asset.

## Contributing

Contributions are welcome — please read [CONTRIBUTING.md](CONTRIBUTING.md) first.
The [Code of Conduct](CODE_OF_CONDUCT.md) applies to all project spaces, and
security issues should be reported privately as described in [SECURITY.md](SECURITY.md).

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
