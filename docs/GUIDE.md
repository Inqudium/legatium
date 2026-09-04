# Legatium — Guide

One structured `adapter_*` log line per outbound HTTP exchange, for two Spring client stacks that share
one contract: the blocking twin [`legatium-restclient-logging`](../legatium-restclient-logging/README.md)
(`RestClient`, `RestTemplate`) and the reactive twin
[`legatium-webclient-logging`](../legatium-webclient-logging/README.md) (`WebClient`) emit the same message
format, the same field family, bind the same `adapter-logging.*` configuration and register the same six
meters. The inbound counterpart of the family is the sibling project
[Limesium](https://github.com/Inqudium/limesium).

This guide holds everything that is **one contract by construction** — integration, configuration, the
fields, the MDC keys, the meters and the trace correlation — so that it is written once. Everything
specific to a stack lives in the twin's own guide: the
[RestClient guide](../legatium-restclient-logging/docs/GUIDE.md) (architecture of the interceptor, its
wiring, the blocking stack's special characteristics) and the
[WebClient guide](../legatium-webclient-logging/docs/GUIDE.md) (architecture of the filter, its wiring,
the reactive stack's special characteristics). Everything here is derived from the code under
`legatium-common/src/main/kotlin/eu/inqudium/legatium/common/` and the twins' entry points; when the
two disagree, the code wins.

## Table of contents

1. [Prerequisites](#1-prerequisites)
2. [Adding the dependency](#2-adding-the-dependency)
3. [Overriding beans](#3-overriding-beans)
4. [Logging backend and structured output](#4-logging-backend-and-structured-output)
5. [Index mapping (ELK)](#5-index-mapping-elk)
6. [Configuration](#6-configuration)
   1. [Property reference](#61-property-reference)
   2. [Header sections](#62-header-sections)
   3. [Body logging and body measuring](#63-body-logging-and-body-measuring)
   4. [Activation: hosts and paths](#64-activation-hosts-and-paths)
   5. [Logger levels](#65-logger-levels)
   6. [Validation at startup](#66-validation-at-startup)
   7. [Example configurations](#67-example-configurations)
7. [Metrics and observation](#7-metrics-and-observation)
   1. [Log fields](#71-log-fields)
   2. [MDC keys](#72-mdc-keys)
   3. [Levels and outcomes](#73-levels-and-outcomes)
   4. [Meters](#74-meters)
   5. [Reading the meters together](#75-reading-the-meters-together)
   6. [Trace correlation](#76-trace-correlation)

---

## 1. Prerequisites

| Requirement | Why |
|---|---|
| **Spring Boot 4.x**, Java 21 | the auto-configurations hook Boot 4's customizer contracts (`org.springframework.boot.restclient.*Customizer`, `org.springframework.boot.webclient.WebClientCustomizer`); on Boot 3 the interceptor or filter bean would exist but never be attached |
| **Boot's client module** — `spring-boot-restclient` (via `spring-boot-starter-restclient`) for the RestClient twin, `spring-boot-webclient` (via `spring-boot-starter-webclient` or `spring-boot-starter-webflux`) for the WebClient twin | the builder beans and the customizer contracts the automatic wiring rests on; **optional** for the modules — without it the bean stays and the host attaches it by hand (manual wiring in the twin's guide). Since Boot 4 the web starters no longer pull `spring-boot-restclient` |
| **Clients built through Boot** | the interceptor or filter is attached to the injected builder bean (`RestClient.Builder`, `RestTemplateBuilder`, `WebClient.Builder`) and to every HTTP service client group built from one; a client built by hand (`RestClient.create()`, `WebClient.create()`, a `RestTemplate()` constructed directly) gets nothing unless the host adds it |
| **An HTTP engine or connector** | the RestClient twin is engine-agnostic (JDK `HttpClient` by default, Apache HttpComponents, ...); the WebClient twin is connector-agnostic and pinned against Reactor Netty, the JDK `HttpClient`, Jetty and Apache HttpComponents 5 by its connector suites |
| **SLF4J 2.x binding** with an encoder that renders key-value pairs and the MDC | the fields ride SLF4J's fluent `addKeyValue`, the identity rides the MDC; Boot's default console pattern prints only the message — [§4](#4-logging-backend-and-structured-output) |
| **A `MeterRegistry` in the host** (actuator) — optional | the six meters are consumed from it, never exported ([ADR-0008](adr/ADR-0008-six-meters-consumed-not-exported.md)); without one the meters are no-ops (an empty `CompositeMeterRegistry`) |
| **The index mapping composed before the first event** — for an ELK target | the component template in [`/docs/elk/`](elk/README.md) keeps body and header fields out of the dynamic mapping ([§5](#5-index-mapping-elk)) |
| Kotlin stdlib | the modules are written in Kotlin; a Java host needs nothing extra, the jars pull `kotlin-stdlib` transitively |

Both modules are **libraries**, not starters: each declares `spring-boot-autoconfigure`, `slf4j-api`,
`micrometer-core`, `kotlin-stdlib`, its client API (`spring-web` resp. `spring-webflux` and
`reactor-core`) and its optional Boot client module — no logging backend, no YAML, no engine or
connector are forced onto the host.

## 2. Adding the dependency

```xml
<dependency>
    <groupId>eu.inqudium</groupId>
    <artifactId>legatium-restclient-logging</artifactId>   <!-- or legatium-webclient-logging -->
    <version><!-- current release: see the badges below --></version>
</dependency>
```

The current release is shown live by the Maven Central badges:
[![Maven Central](https://img.shields.io/maven-central/v/eu.inqudium/legatium-restclient-logging.svg?label=restclient)](https://central.sonatype.com/artifact/eu.inqudium/legatium-restclient-logging)
[![Maven Central](https://img.shields.io/maven-central/v/eu.inqudium/legatium-webclient-logging.svg?label=webclient)](https://central.sonatype.com/artifact/eu.inqudium/legatium-webclient-logging)
— there is no BOM; the version is declared on the dependency itself. An application may carry both
twins (a servlet host using `RestClient` for most calls and `WebClient` for a streaming one gets both
logged, in one format); keep them at the same version, both jars inline the same shared classes
([ADR-0003](adr/ADR-0003-legatium-common-inlined-by-shade.md)).

That is all: the auto-configuration registers the interceptor or filter and its customizers (the twin
guides describe the automatic and the manual wiring), every call through a Boot-built client is logged
on the `http-adapter-exchange` logger at INFO, the request id comes from the `traceparent` trace id
(traceless calls send an `X-Correlation-Id` instead —
[ADR-0002](adr/ADR-0002-trace-id-is-the-request-id.md)), and the six meters are registered in the
host's `MeterRegistry` if one exists.

To remove a module again without touching the classpath:

```yaml
adapter-logging:
  enabled: false
```

## 3. Overriding beans

Every default is `@ConditionalOnMissingBean`, and one host bean serves **both** twins — the shared types
live in the package `eu.inqudium.legatium.common`:

```kotlin
@Configuration(proxyBeanMethods = false)
class ClientLoggingCustomisation {

    /** Deterministic ids in a test profile, or a different id format (a peer that insists on UUIDs). */
    @Bean
    fun correlationIdGenerator(): CorrelationIdGenerator =
        CorrelationIdGenerator { UUID.randomUUID().toString() }

    /** A keyed fingerprint where an unkeyed hash is not acceptable; both twins mask with this one bean. */
    @Bean
    fun headerValueMasker(secrets: Secrets): HeaderValueMasker =
        HeaderValueMasker { value -> "hmac:" + secrets.hmacSha256Hex(value).take(16) }

    /** Only if the host owns a monotonic clock abstraction already. */
    @Bean
    fun nanoTimeSource(clock: MonotonicClock): NanoTimeSource =
        NanoTimeSource { clock.nanos() }
}
```

A host-defined `ClientRequestLoggingInterceptor` (RestClient twin) or `ClientRequestLoggingFilter`
(WebClient twin) bean replaces the **entry point**, not the wiring: the auto-configured customizers
still attach it to every Boot-built client. Both constructors take
`(ClientLoggingProperties, NanoTimeSource, CorrelationIdGenerator, MeterRegistry)` plus an optional
trailing `HeaderValueMasker` — when omitted, the masker the properties' `masking-key` selects
(`HeaderValueMasker.forKey`), so a hand-built entry point honours a configured key exactly like the
auto-configured one; the property is the one source of truth, and only an explicitly passed masker (a
host bean) overrides it:

```kotlin
@Bean
fun clientRequestLoggingInterceptor(
    properties: ClientLoggingProperties,
    nanoTime: NanoTimeSource,
    ids: CorrelationIdGenerator,
    registry: MeterRegistry,
): ClientRequestLoggingInterceptor = ClientRequestLoggingInterceptor(properties, nanoTime, ids, registry)
```

Attaching the (default or replaced) entry point to a client that does not pass through Boot's builders
is the manual-wiring section of the twin's guide. Keep in mind the one-instance-per-registry rule of the
open-exchanges gauge ([§7.4](#74-meters)).

## 4. Logging backend and structured output

The modules emit through SLF4J's fluent API. Every exchange event carries its data in **two places**,
and an encoder treats them differently:

| Data | Carried as | Examples |
|---|---|---|
| The field family | SLF4J **key-value pairs** (`addKeyValue`) | `adapter_outcome`, `adapter_duration_ms`, `adapter_url_host`, `adapter_response_body` |
| The identity and trace context | **MDC** entries, set by the emission scope (and, on the blocking stack, by the call scope around the wire call) | `adapter_request_id`, `adapter_method`, `adapter_route`, `traceId`, `spanId` (from the `traceparent` header) |

A plain `%msg` pattern shows neither — only the message, which repeats the gist inline
(`… -> 200 [adapter_request_id=…]`) precisely for that case. Logback offers three ways to render the
rest; which one fits depends on where the output goes.

### Option 1 — `PatternLayout` with `%kvp` and `%mdc` (text, for terminals and files)

```xml
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
        <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg %kvp{NONE} [%mdc]%n</pattern>
    </encoder>
</appender>
```

```
13:54:58.534 INFO  [http-nio-8080-exec-3] http-adapter-exchange - Adapter http exchange POST https://api.example.com/things/42 -> 200 [adapter_request_id=4bf9… traceId=4bf9… spanId=00f0…] adapter_outcome=success adapter_duration_ms=17 adapter_request_method=POST adapter_response_status_code=200 adapter_url_host=api.example.com adapter_url_path=/things/42 adapter_url_template=https://api.example.com/things/{id} [adapter_method=POST, adapter_request_id=4bf9…, adapter_route=https://api.example.com/things/42, endpoint_request_id=4bf9…, traceId=4bf9…, spanId=00f0…]
```

(On the reactive stack the thread reads `reactor-http-epoll-2` or the like; the line is otherwise identical.)

- `%kvp` quotes values with double quotes by default; `%kvp{NONE}` leaves them bare.
- `%X{adapter_request_id:-}` prints one key and nothing when it is absent; `%mdc` prints every entry
  that is present as `key=value`, so the trace keys appear only on traced calls.
- In Spring Boot the same pattern goes into `logging.pattern.console` without any XML.
- This is the modules' own test configuration (`src/test/resources/logback-test.xml`).
- **Text output renders values raw.** The logged path and query are percent-encoded as sent, but bodies
  (opt-in) may contain line breaks — mind that before pointing a text appender at a pipeline that parses
  lines.

### Option 2 — Logback's `JsonEncoder` (JSON without an extra dependency, Logback ≥ 1.4.3)

```xml
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="ch.qos.logback.classic.encoder.JsonEncoder">
        <withSequenceNumber>false</withSequenceNumber>
        <withNanoseconds>false</withNanoseconds>
    </encoder>
</appender>
```

One JSON object per event, control characters escaped — but the key-value pairs arrive as a **list of
single-key objects** and the MDC nested under `"mdc":{…}`. Correct and safe, yet awkward to map onto the
flat `adapter_*` fields of the index template; suitable for local JSON inspection, not for an index.

### Option 3 — Spring Boot structured logging (JSON, flat, typed — recommended for an index)

```yaml
logging:
  structured:
    format:
      console: ecs      # or logstash, gelf
  level:
    http-adapter-exchange: INFO
    eu.inqudium.legatium.restclient.logging: WARN   # resp. eu.inqudium.legatium.webclient.logging
```

Key-value pairs and MDC entries become **flat top-level fields**, and values keep their JVM type —
`adapter_duration_ms` is a number, `adapter_response_status_code` a number, the shape each field
declares in `ClientLogField` and the component template maps. This is the shape the template in
[§5](#5-index-mapping-elk) is written for. `logging.structured.json.include` / `exclude` / `rename`
control the field selection (e.g. to drop `adapter_route`, which duplicates host and path).

| Option | Output | Key-value pairs | MDC | Typed values | Escapes control chars | Use for |
|---|---|---|---|---|---|---|
| 1 `PatternLayout` `%kvp` `%mdc` | text | inline `k=v` | inline `k=v` | no (all text) | **no** | terminals, local files, tests |
| 2 `JsonEncoder` | JSON | list of objects | nested `mdc` | partly | yes | local JSON inspection |
| 3 `StructuredLogEncoder` | JSON | flat fields | flat fields | **yes** | yes | **log index (ELK etc.)** |

Whatever the option, keep the module's own logger (`eu.inqudium.legatium.restclient.logging` resp.
`eu.inqudium.legatium.webclient.logging`) at WARN or lower: it carries the WARN breadcrumb on a thrown
call (blocking stack) and the modules' own failure reports.

## 5. Index mapping (ELK)

The thirteen `adapter_*` fields have a ready-made Elasticsearch component template in
[`/docs/elk/`](elk/README.md):

```bash
curl -X PUT "$ES/_component_template/legatium-restclient-logging-fields" \
     -H 'Content-Type: application/json' \
     --data-binary @docs/elk/legatium-restclient-logging-fields.component-template.json
```

Compose it into the data-stream mapping **before** the first event arrives — an unmapped body or header
field would be mapped dynamically and become searchable, which the payload fields' `index: false`
deliberately prevents. The MDC-carried keys are intentionally not in the template: where they land
depends on the host's encoder layout; map them where the encoder configuration lives. The one template
serves both twins (the field family is one enum, `ClientLogFieldTest` keeps it in lockstep) and composes
beside Limesium's `endpoint_*` template without collision.

---

## 6. Configuration

All properties live under `adapter-logging.*`. The complete, commented reference with every default is
[`/docs/adapter-logging-reference.yml`](adapter-logging-reference.yml);
`ClientLoggingReferenceConfigTest` (in `legatium-common`) binds it against the shared
`ClientLoggingProperties` and fails the build on any drift — every key must exist, every value must be
the built-in default. Both twins bind that one class, so the namespace is identical across the stacks
by construction.

### 6.1 Property reference

| Property | Type | Default | Meaning |
|---|---|---|---|
| `enabled` | boolean | `true` | Master switch. `false` makes the auto-configuration back off — no interceptor or filter, no customizers, no beans. A context-start decision, not a runtime toggle. |
| `logger-name` | string | `http-adapter-exchange` | Logger of the arrival line and the exchange event. Its level is the runtime volume control ([§6.5](#65-logger-levels)). Distinct from Limesium's `http-exchange` by design. |
| `correlation-id-header` | string (RFC 9110 token) | `X-Correlation-Id` | Header read from a **traceless** request (no conformant `traceparent` — ADR-0002); when absent, or outside the acceptance rule (at most 200 visible-ASCII characters, ADR-0002 amendment), an id is generated and SET on the request under this name so the peer can quote it. A traced call takes its request id from the `traceparent` trace id, ignores this header and adds nothing. |
| `include-query-string` | boolean | `true` | Log the query string as its own field `adapter_url_query` (never part of the path). Disable when query parameters may carry personal data. |
| `log-request-start` | boolean | `false` | Additionally log an arrival line before the call, at INFO, under the emission MDC. Carries no outcome/status/duration. |
| `include-path-patterns` | list of `PathPattern` | `[]` | Request paths the logging is active for at all, whatever the host; empty = every call. Parsed once at startup; an invalid pattern fails the context. |
| `exclude-path-prefixes` | list of strings | `[]` | Request-path prefixes skipped entirely — no event, no MDC, no correlation header, no gauge movement. Prefix match against the decoded path. An exclude always wins over an include. |
| `exclude-hosts` | list of strings | `[]` | Peer hosts skipped entirely (case-insensitive, without port) — the outbound counterpart of excluding a health probe. |
| `slow-request-threshold` | duration | `5s` | At/above this duration an INFO call escalates to WARN and is flagged `adapter_slow: true`; the outcome stays `success`. Measured until the exchange is truly over — response close (RestClient) or the body's terminal signal (WebClient): response occupancy, not bare round-trip time. Must be ≥ 1 ms. |
| `request-headers.includes` / `.excludes` / `.masked` / `.unmasked` | lists of header names | see [§6.2](#62-header-sections) | The request-header section. |
| `response-headers.includes` / `.excludes` / `.masked` / `.unmasked` | lists of header names | see [§6.2](#62-header-sections) | The response-header section. |
| `log-request-body` | `never` \| `on-failure` \| `always` | `never` | Log the request body into `adapter_request_body`, up to `max-body-bytes` — on every line (`always`) or only when the outcome is not `success` or the status is a 4xx (`on-failure`, [§6.3](#63-body-logging-and-body-measuring)). On the blocking stack this is the serialized body the client **hands to the wire call** — copied before the call, so a refused connection still shows what was about to be sent; on the reactive stack it is teed as the inserter **writes** it to the connector. |
| `log-response-body` | `never` \| `on-failure` \| `always` | `never` | Tee the response body into `adapter_response_body` as the application reads it, up to `max-body-bytes` — on every line or only when the outcome is not `success` or the status is a 4xx. |
| `measure-request-body-size` | boolean | `false` | Record `adapter.request.body.size`; independent of `log-request-body`. On the blocking stack a sample is recorded only for an exchange that **received a response** — the one proof the interceptor's seam has that the request went out ([§7.4](#74-meters)). |
| `measure-response-body-size` | boolean | `false` | Record `adapter.response.body.size` and `adapter.response.body.read`; independent of `log-response-body`. |
| `max-body-bytes` | int > 0 | `16384` | Capture limit per body. Bounds **memory** (and, on the reactive stack, the tee's transient copy per buffer), not the exchange: bytes beyond it still flow; the logged value is truncated with a note of the total size. |
| `masking-key` | string | *(empty)* | Keys the masking fingerprint: empty keeps the unkeyed `length:hash`, any other value turns it into an HMAC-SHA256 under the key — same shape, same stability under the same key, guess-proof without it. A **secret**: supply it like one; the properties' `toString` redacts it. Ignored when a host pins its own `HeaderValueMasker` bean. |

### 6.2 Header sections

Each direction has one section with four lists; matching is case-insensitive throughout. The section
is **masked by default** ([ADR-0005](adr/ADR-0005-headers-masked-by-default.md)): whatever it logs is
rendered as a fingerprint unless the name is explicitly allowed in plaintext, so the debugging move
`includes: ["*"]` costs readability, never confidentiality.

| List | Semantics |
|---|---|
| `includes` | Names to log. **Empty logs nothing** (the safe default). The entry `*` logs every header the message carries; names are deduplicated case-insensitively on both paths. |
| `excludes` | Names removed from the included set — meaningful mainly with `*`. An exclude always wins. `*` is rejected here at binding time (an empty `includes` already logs nothing). |
| `masked` | Names whose **value** is replaced by what the `HeaderValueMasker` bean renders — by default a fingerprint `length:hex`, the character length plus the first 64 bits of the SHA-256 of the UTF-8 value, e.g. `18:930bbdc51b6aed5c` (a **pseudonym**, not anonymisation: equal values stay recognisable as equal; key it with `masking-key` to stop guess confirmation). **Default `["*"]`: every logged header is masked** (ADR-0005). Narrow it to names, or empty it to switch masking off — a visible decision. Masking affects only headers that are logged; listing a name here does not include it. |
| `unmasked` | Names that appear in **plaintext** although `masked` covers them — the explicit allowlist of harmless names (`Content-Type`, `Accept`, a correlation id). An unmasked name always wins over a masked one. `*` is rejected here: the plaintext set is a list of names by design; to log everything in plaintext, empty `masked` instead. |

Multi-valued headers are joined with `, `. The selected pairs are rendered into one display-only field
per direction as `[Name:"value", Name2:"value2"]`; nothing is emitted when the selection is empty or no
selected header is present.

Request headers are selected at **wiring time** from the outgoing request, after the correlation header
was added, so a selected `X-Correlation-Id` shows what actually went out; response headers at
**emission** (response close, resp. the body's terminal signal), so they reflect the response as received.

### 6.3 Body logging and body measuring

Per direction, a **mode** decides whether a body is logged and a **flag** decides whether its size is
measured — independent of each other:

| `log-*-body` | `measure-*-body-size` | Capture installed | Buffered | Effect |
|---|---|---|---|---|
| `never` | off | no | — | request body untouched; response passes through (the blocking stack keeps a read-failure guard, the reactive stack the terminal hooks) |
| `always` | off | yes, limit `max-body-bytes` | up to the limit | field logged on every line; no size sample |
| `on-failure` | off | yes, limit `max-body-bytes` | up to the limit | field logged only when `adapter_outcome` is not `success` or the status is a 4xx; no size sample |
| `never` | on | yes, limit `0` (count-only) | nothing | size sample recorded; no field |
| `always` / `on-failure` | on | yes, limit `max-body-bytes` | up to the limit | both |

**`on-failure` is the volume switch** ([ADR-0006](adr/ADR-0006-bodies-logged-by-outcome.md)).
`always` means every body of every call; what is nearly always wanted is bodies for the calls that went
wrong — `failure`, `timeout`, on the reactive stack `cancelled` — which cuts the volume by orders of
magnitude and hits exactly the lines a body is wanted for. The response side decides at emission, when
the outcome is final. The request body flows before the outcome is known, so `on-failure` captures it
exactly like `always` does (bounded by `max-body-bytes`) and discards it for a success: the capture is
paid, the output is saved — and the output is what burdens the log pipeline. The gate is wider than the
outcome vocabulary ([§7.3](#73-levels-and-outcomes)) by one status class: a `4xx` answer keeps its
`success` outcome — the peer answered — but its bodies are logged in `on-failure`, because the client's
error is exactly what the body explains; a `5xx` is `failure` and logs as well. A slow but healthy call
stays `success` and logs no bodies.

Rules that hold for every combination:

- The captures are passive: bytes are counted and (up to the limit) copied as they flow — the request
  body from the byte array the client hands the interceptor (blocking stack) or as the inserter writes
  it to the connector (reactive stack), the response body as the application reads it; nothing is
  pre-read, replayed or withheld. Backpressure and streaming behaviour are untouched.
- An **unread response body** is logged as absent; no size sample is recorded — on the reactive stack the
  exchange does not complete until something consumes or releases the body.
- Zero-byte bodies produce no field and no sample — the distribution describes bodies that exist.
- Truncation is **byte-bounded**, and the decoder leaves an incomplete trailing multi-byte sequence
  undecoded rather than rendering a replacement character: `…<prefix>... [truncated, 12345 bytes total]`.
- The log charset is the one the `Content-Type` declares (request: the caller's; response: the peer's),
  UTF-8 when absent or unparsable.
- `measure-*` records what actually flowed, **exact beyond** `max-body-bytes`. Field and meter are
  deliberately decoupled on the blocking stack's request side: the interceptor sees the serialized body
  *before* the wire call, so the **field** shows the body the client handed to the call (evidence, also
  for a refused connection), while the **meter** records a sample only once a response proves the
  request went out.
- `measure-response-body-size` additionally records `adapter.response.body.read` — whether the application
  consumed the body completely, partially, or not at all ([§7.4](#74-meters)).
- On the reactive stack the captures are **frozen at emission**: a body chunk still in flight after a
  cancellation can no longer change what was logged. A zero-copy file upload (Reactor Netty's `sendfile`)
  keeps its path under request capture; its bytes are counted, not copied.

**Cardinality of the body meters.** The size summaries and the read-state counter are tagged `uri` and
`host`. The `uri` tag is the URI template the client recorded — and only when it carries a placeholder:
both clients record whatever string was passed to `uri(String, ...)`, so a concatenated
`uri("/things/" + id)` would otherwise put one tag value per id on the meter; such values fold to
`UNKNOWN` (as does every `RestTemplate` call, which records no template). The `host` tag is
caller-controlled and is **not** folded: a host that fans out to many peer hosts (webhooks, per-tenant
endpoints) should leave the measuring properties off or accept one tag set per host in its registry.

### 6.4 Activation: hosts and paths

```
active(uri) = uri.host not in exclude-hosts
              AND (include-path-patterns is empty  OR  any pattern matches uri.path)
              AND no exclude-path-prefix is a prefix of uri.path
```

An inactive call passes through **without any trace**: no correlation header, no MDC, no event, no gauge
movement, no counters (on the reactive stack the connector receives the caller's very request object).
The activation is one shared implementation (`ClientActivation`), so its semantics are identical on both
stacks by construction. Typical use:

```yaml
adapter-logging:
  exclude-hosts:
    - pushgateway.monitoring.svc
    - config-server
  exclude-path-prefixes:
    - /internal/
```

`include-path-patterns` uses Spring's `PathPattern` syntax (`/api/**`, `/api/{*rest}`) against the request
**path** whatever the host; `exclude-path-prefixes` is a prefix match; `exclude-hosts` an exact,
case-insensitive match on the URI's host without port. Path matching sees the target the way a server
router would — segments **decode for matching** — so `/%61pi/things` is included by `/api/**`,
`/api%2Fthings` is not, and `/%61ctuator/health` is excluded by `/actuator/health`. The logged
`adapter_url_path` stays raw.

### 6.5 Logger levels

Severity and semantic are decoupled: the level only decides how loud — and whether — a line is emitted;
`adapter_outcome` carries the disposition ([§7.3](#73-levels-and-outcomes)). The level of the
`logger-name` logger therefore acts as the runtime volume control:

| `http-adapter-exchange` level | Emitted |
|---|---|
| `INFO` | every call |
| `WARN` | failures (5xx), timeouts, slow calls, cancellations (reactive stack) — and thrown or errored calls |
| `ERROR` | only calls that threw or errored (no response, or the body read failed) |
| `OFF` | nothing — and no event is even assembled |

Level and outcome are resolved **before** the event is built, so a disabled level costs no assembly, no
header selection, no body decoding. Metrics are recorded **before** the level gate and are unaffected by
it — except `adapter.logging.events`, which by definition counts emitted events only.

### 6.6 Validation at startup

`ClientLoggingProperties.init` and `HeaderLogProperties.init` reject, with a message naming the property:

- blank `logger-name` or `correlation-id-header`;
- a `correlation-id-header` that is not an RFC 9110 token (it is written onto every traceless request; an
  engine or connector that validates field names — the JDK client does — would reject a non-token per
  call, failing the CALL, not merely the log line);
- `max-body-bytes` ≤ 0;
- a blank (whitespace-only) `masking-key` — empty means unkeyed, whitespace is a worthless secret;
- `slow-request-threshold` < 1 ms (the logged duration has millisecond resolution);
- blank entries in any list (`exclude-hosts` included);
- `*` in an `excludes` or an `unmasked` list;
- an unparsable `include-path-patterns` entry (parsed once at construction of the entry point).

### 6.7 Example configurations

**Minimal production profile** — everything logged, telemetry peers excluded, slow threshold tightened:

```yaml
adapter-logging:
  exclude-hosts:
    - pushgateway.monitoring.svc
  slow-request-threshold: 2s
logging:
  level:
    http-adapter-exchange: INFO
    eu.inqudium.legatium.restclient.logging: WARN   # resp. eu.inqudium.legatium.webclient.logging
```

**Diagnostics profile** — headers with masked credentials, both bodies, arrival lines:

```yaml
adapter-logging:
  log-request-start: true
  log-request-body: always
  log-response-body: always
  max-body-bytes: 16384
  request-headers:
    includes: ["*"]
    excludes: [Cookie]
    unmasked: [Accept, Content-Type, X-Correlation-Id]   # everything else stays a fingerprint
  response-headers:
    includes: [Content-Type, Content-Length, Retry-After]
    unmasked: [Content-Type, Content-Length, Retry-After]
```

**Production profile with bodies** — bodies only for the calls that went wrong; the request body is
captured up to `max-body-bytes` per call and dropped on success:

```yaml
adapter-logging:
  log-request-body: on-failure
  log-response-body: on-failure
  max-body-bytes: 4096
```

**Metrics without log volume** — body sizes and consumption measured, only failures logged:

```yaml
adapter-logging:
  measure-request-body-size: true
  measure-response-body-size: true
logging:
  level:
    http-adapter-exchange: WARN
```

---

## 7. Metrics and observation

### 7.1 Log fields

The structured fields of the completion event (the arrival line carries method, host, path, template,
query and request headers without outcome/duration/status). The index types are those of the shared
component template; `ClientLogFieldTest` in `legatium-common` keeps the shared enum in lockstep with it.

| Field | Type | Index | doc_values | When present | Notes |
|---|---|---|---|---|---|
| `adapter_outcome` | keyword | yes | on | always | `success` / `failure` / `timeout`, plus `cancelled` on the reactive stack — the field dashboards split by; decoupled from the level |
| `adapter_duration_ms` | long | yes | on | always | from the injected monotonic source; until response close (RestClient) resp. the body's terminal signal (WebClient) |
| `adapter_request_method` | keyword | yes | on | always | |
| `adapter_response_status_code` | short | yes | on | when a response arrived | absent for a refused connection, a timeout before the status line, or a cancellation before the response (`-> -`) |
| `adapter_url_host` | keyword | yes | on | when the URI has a host | `host` or `host:port` — the outbound coordinate |
| `adapter_url_template` | keyword | yes | on | when the client recorded a template | the aggregation half of the path pair, e.g. `https://api.example.com/things/{id}`; never for `RestTemplate` |
| `adapter_url_path` | keyword | yes | **off** | always | the **raw** path as sent, ids and all — filter exactly, never group |
| `adapter_url_query` | keyword | yes | **off** | when the request had one and `include-query-string` is on | raw, as sent |
| `adapter_slow` | boolean | yes | on | only when the threshold was reached | absence means fast |
| `adapter_request_headers` | keyword | **no** | off | when selected headers are present | display only, rendered `[Name:"value", …]` |
| `adapter_response_headers` | keyword | **no** | off | when selected headers are present | display only |
| `adapter_request_body` | keyword | **no** | off | when `log-request-body` admits the outcome and the client handed a non-empty body to the wire call (blocking stack — present even when the call was refused) resp. bytes were written to the connector (reactive stack) | display only, bounded |
| `adapter_response_body` | keyword | **no** | off | when `log-response-body` admits the outcome and bytes were read | display only, bounded |

Each field declares the exact JVM type of its value (`ClientLogField`), which the lockstep test checks
against the template; the emitters are the only writers.

The throwable of a failed call is attached to the event as its cause (`setCause`), so a structured
encoder renders the stack trace alongside the fields.

### 7.2 MDC keys

Set by `MdcScope` around each emission — and, on the blocking stack, around the wire call:

| Key | Value | Scope |
|---|---|---|
| `adapter_request_id` | the request id: the `traceparent` trace id, or the accepted/generated correlation id (ADR-0002) — always set | emission; on the blocking stack also the call |
| `adapter_method` | the HTTP method | same |
| `adapter_route` | the request **target**: `scheme://host[:port]/path`, query excluded — for an outbound call the host is as much part of the route as the path | same |
| `traceId` | trace id from `traceparent` | emission only (owned) |
| `spanId` | parent-id from `traceparent` — the **local client span** the peer will see as its parent | emission only (owned) |

`MdcScope` restores the previous value of every key on close, rolls back a partial install if the
adapter throws mid-put, and restores best-effort on close with the first failure rethrown and later ones
suppressed. It never removes keys it does not own: an inbound request's identity stays. The reactive
stack has no call-wide thread-local scope — the call hops event-loop threads; propagating the identity
into reactive operators is the host's context-propagation business.

### 7.3 Levels and outcomes

Resolved in this order in each twin's `ExchangeLogEmitter`:

| Condition | Level | `adapter_outcome` |
|---|---|---|
| the call threw or errored and a timeout is in the cause chain (or among the suppressed exceptions) | `WARN` | `timeout` |
| the call threw or errored (no response), or the body read threw or errored | `ERROR` | `failure` |
| the subscription was abandoned by the caller — reactive stack only (a timeout operator, a disposed caller, a disconnect; a consumer that stops reading the body because it has read enough is `success`) | `WARN` | `cancelled` |
| status ≥ 500 without an exception (the peer answered; the application decides) | `WARN` | `failure` |
| otherwise | `INFO` | `success` |
| … and the duration reached `slow-request-threshold` | `INFO → WARN` | unchanged, plus `adapter_slow: true` |

A 4xx is a `success` at INFO: the peer answered as designed, and whether a 404 is a problem is the
application's call — the status is on the line for the dashboard to split by. Slowness raises severity;
it never turns a completed call into a failure.

### 7.4 Meters

Six meters, all **consumed** from the host's `MeterRegistry` (an `ObjectProvider`; without one the
auto-configuration passes an empty `CompositeMeterRegistry`, whose meters are Micrometer no-ops) — one
shared implementation (`ClientLoggingMetrics`), parameterised by the stack. Why these six and no others,
and the rule for adding one, is [ADR-0008](adr/ADR-0008-six-meters-consumed-not-exported.md). All fixed-tag meters are **pre-registered at construction**, so a `rate()`
alert sees the zero before the first occurrence. Rates, latencies and status distributions are
deliberately left to `http.client.requests` and the log fields.

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `adapter.logging.failopen` | counter | `stage` = `emission` \| `arrival` \| `wiring` | Logging failures the fail-open path swallowed. `emission`: an exchange event was **lost**. `arrival`: a start line was lost. `wiring`: bookkeeping failed (pass-through degradation, a lost sample or counter) — the event usually still follows. A lost log line cannot report itself through the same pipeline; this counter is the independent channel. |
| `adapter.logging.events` | counter | `outcome` = `success` \| `failure` \| `timeout` (\| `cancelled` on the reactive stack) | Exchange events actually **emitted** on the exchange logger — after the level gate, arrival lines excluded. The reconciliation ground truth against the log index. |
| `adapter.logging.exchanges.open` | gauge | `client` = `restclient` \| `webclient` | Exchanges between entry (wiring) and the exactly-once completion — response close, resp. the body's terminal signal. Hovers near the in-flight call count in health. Tagged per twin so that a host carrying both twins gets two gauges instead of Micrometer silently keeping the first one registered; sum over `client` for the total. |
| `adapter.logging.correlation.id` | counter | `source` = `trace` \| `header` \| `generated` | Origin of each call's request id (ADR-0002). A re-entry by a retrying outer interceptor with the id generated on attempt 1 keeps counting `generated`. |
| `adapter.response.body.read` | counter | `uri` = template with a placeholder, `UNKNOWN` otherwise; `host`; `state` = `unread` \| `partial` \| `complete` | How far the application **consumed** the response body, opt-in via `measure-response-body-size`. Recorded once per call that received a response — including bodiless consumption, which is the `unread` share the counter exists to show. `partial` = consumption started but the end of the body was never observed (a converter that stopped early, an exception mid-read, a consumer that stopped reading). On the blocking stack the end is observed either as the EOF or as the byte count reaching a trustworthy declared `Content-Length` (none with a `Content-Encoding`), because Spring's `ByteArrayHttpMessageConverter` reads exactly that many bytes and never asks for the EOF. Created lazily per tag set on first use. |
| `adapter.request.body.size` / `adapter.response.body.size` | distribution summary, base unit `bytes` | `uri`, `host` | Bytes that **actually flowed**, opt-in via `measure-*-body-size`, independent of body logging and level. Exact beyond `max-body-bytes`. Zero-byte bodies record no sample. On the blocking stack the **request** sample is recorded only for an exchange that received a response: the interceptor copies the serialized body before the wire call and has no seam at the actual write, so a response is its one proof that the bytes went out (a refused connection or connect timeout records nothing; the reactive stack tees at the connector's write and needs no such rule). Created lazily per tag set on first use. |

**One instance per registry and stack.** Micrometer deduplicates meters by id, so a second metrics
owner against the same registry would share the counters but not the gauge — the second gauge
registration is silently ignored. Every entry point therefore obtains its metrics owner through a
per-registry cache; several interceptors or filters on one registry share one owner, and the gauge
reports the total across them.

**Registration conflicts.** Micrometer rejects a registration whose id already exists with a different
meter type. Rather than aborting the context (at construction) or suppressing an exchange event (at the
lazy body-size registration), the conflicting meter falls back to a private registry, warned once per
meter name on `eu.inqudium.legatium.common.ClientLoggingMetrics`: the module keeps working and that
meter is simply not exported. The gauge has a second case Micrometer does **not** reject: a gauge of the
same id already registered by the host or by another copy of the library (another classloader) would be
returned as-is and the module's state silently dropped — the gauge would show the foreign value. The
registration therefore checks for an existing meter under its exact name and `client` tag first and takes
the same private-registry path with the same warning: visibly degraded, never silently wrong.

### 7.5 Reading the meters together

The meters are designed to cover each other's blind spots:

| Question | Signal |
|---|---|
| Are exchange events being lost **loudly** (something threw)? | `failopen{stage=emission}` > 0 |
| Are exchange events being lost **silently** (nothing threw; a response was never closed, a body never consumed)? | `exchanges.open` baseline grows monotonically instead of returning towards 0 |
| Is the **log pipeline** (appender, broker, index) losing events? | `sum(adapter.logging.events)` over a window ≠ count of indexed `http-adapter-exchange` documents for the same window |
| Did the application stop propagating identity onto its calls? | the `generated` share of `correlation.id` rises (in a host with tracing configured it is zero by construction — [§7.6](#76-trace-correlation)) |
| Are callers abandoning their own calls (operator timeouts, disconnects)? — reactive stack | `events{outcome=cancelled}` rises while `timeout` does not |
| Is a call site discarding the payload it paid for? | the `unread` or `partial` share of `response.body.read{uri=...,host=...}` rises |
| Are payloads growing beyond what the log captures? | `body.size` percentiles vs. `max-body-bytes` |
| Which dependency is slow, or failing? | `adapter_url_host` on the log line, split by `adapter_outcome` — not a meter of this module; `http.client.requests` has the latency histogram |

A suggested alert set:

```promql
# lost exchange events (hard failure)
increase(adapter_logging_failopen_total{stage="emission"}[5m]) > 0

# silently stuck exchanges (liveness) - tune the bound to the service's outbound concurrency
min_over_time(adapter_logging_exchanges_open[15m]) > 50

# callers tearing down their own calls (reactive stack)
sum(rate(adapter_logging_events_total{outcome="cancelled"}[10m])) > 0

# timeouts per peer (from the log index, not a meter): adapter_outcome=timeout by adapter_url_host
```

Note on the gauge: a response the application holds open deliberately (a streaming download) is
**intended** to stay open — that is the liveness signal working, not a leak to suppress.

### 7.6 Trace correlation

The modules read the **outgoing W3C `traceparent` header**, put on the request by the host's tracing
propagation:

```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
                 └──────── traceId ───────────────┘ └──── spanId ────┘
```

- `traceId` is the trace the client span runs under, published under Boot's logging-correlation key
  `traceId`, so the log-to-trace join holds.
- The header's parent-id is the span the peer will treat as its parent — which IS the local client span
  of this call. It is published under Boot's local-span key `spanId`. (Inbound, Limesium publishes the
  same header field as `parentSpanId`, because there it is the caller's span; the two projects are
  consistent, not identical.)
- Parsing follows the W3C Trace Context Recommendation strictly: lowercase hex of fixed length, no
  all-zero ids, version `ff` forbidden, version `00` exactly four fields, higher versions parsed by the
  version-00 rules for their first four fields. A non-conformant header is ignored — nothing is logged,
  and the call counts as traceless for the identity decision. The conformance is pinned by
  `traceparent/conformance.txt`, the same fixture Limesium uses.
- Since ADR-0002 the trace id also **is** the call's `adapter_request_id`, and a traced call gets no
  correlation header — the identity decision and the trace fields share the one strict parse.

**Where the header comes from.** With Micrometer Tracing on the classpath, Boot registers the client
observation (`RestClientObservationAutoConfiguration` resp. the `RestTemplate` variant;
`WebClientObservationAutoConfiguration`); when the observation starts, the tracing handler opens the
client span and **injects `traceparent`** — into the request headers before the interceptor chain runs
(`RestClient`), into the request *builder* before the request is built and the filter chain runs
(`WebClient`) — so every interceptor or filter, this one included, sees the header. Each twin's tracing
integration test pins that order beside a real Brave bridge; a Boot upgrade that changes it breaks the
build rather than silently dropping trace ids. Consequence: in a host with tracing configured, **every
call is traced** and the module never generates an id there — the `generated` share of
`adapter.logging.correlation.id` reads zero by construction.
