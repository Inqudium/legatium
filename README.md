<p align="center">
  <img src="docs/logo/legatium-banner.svg" alt="Legatium — one structured adapter_* line per outbound HTTP exchange" width="640">
</p>

[![Maven Central](https://img.shields.io/maven-central/v/eu.inqudium/legatium.svg?label=Maven%20Central)](https://central.sonatype.com/namespace/eu.inqudium)
[![CI](https://github.com/Inqudium/legatium/actions/workflows/ci.yml/badge.svg)](https://github.com/Inqudium/legatium/actions/workflows/ci.yml)
[![Coverage](https://inqudium.github.io/legatium/coverage/badge.svg)](https://inqudium.github.io/legatium/coverage/)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Last commit](https://img.shields.io/github/last-commit/Inqudium/legatium)](https://github.com/Inqudium/legatium/commits/main)
[![Issues](https://img.shields.io/github/issues/Inqudium/legatium)](https://github.com/Inqudium/legatium/issues)
[![Docs](https://img.shields.io/badge/docs-inqudium.github.io-8E2C21)](https://inqudium.github.io/legatium/)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/Inqudium/legatium/badge)](https://scorecard.dev/viewer/?uri=github.com/Inqudium/legatium)

Legatium logs one structured adapter_* line per outbound HTTP exchange — named after the Roman
legatus, the envoy a service sends to a foreign party, and the record of what came of it. Two
auto-configured Spring Boot twins with identical fields and configuration: a RestClient/RestTemplate
interceptor and a WebClient filter. No starter, no forced transitives.

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
status, peer host, URI template, path, query, optional headers and bodies — and the identity in the MDC.
The trace ids come from the `traceparent` header the host's tracing propagation put on the request; on a
traceless call the module sends an `X-Correlation-Id` instead, so the peer can quote it. Outcomes:
`success`, `failure`, `timeout`, and on the reactive stack `cancelled`.

## Documentation

**Documentation site:** [inqudium.github.io/legatium](https://inqudium.github.io/legatium/) —
guides, Elasticsearch mapping, generated [test evidence](https://inqudium.github.io/legatium/tests/test-evidence/),
[coverage reports](https://inqudium.github.io/legatium/coverage/), and the Dokka
[API](https://inqudium.github.io/legatium/api/legatium-restclient-logging/)
[references](https://inqudium.github.io/legatium/api/legatium-webclient-logging/).

- [Legatium guide](docs/GUIDE.md) — everything that is one contract for both twins, written once:
  prerequisites, dependency, overriding beans, logging backend, index mapping, configuration, fields,
  MDC keys, meters, trace correlation.
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

Each Legatium release is built and tested against one Spring Boot and one Kotlin line; the table is the
history of those lines, newest first. The artifacts target Java 21 (the build itself needs JDK 24+).

| Legatium | Spring Boot | Kotlin |
|---|---|---|
| 1.0.0 (unreleased) | 4.1.x | 2.4.x |

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

## Contributing

Contributions are welcome — please read [CONTRIBUTING.md](CONTRIBUTING.md) first.
The [Code of Conduct](CODE_OF_CONDUCT.md) applies to all project spaces, and
security issues should be reported privately as described in [SECURITY.md](SECURITY.md).

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
