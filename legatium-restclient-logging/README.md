# legatium-restclient-logging

Auto-configured `ClientHttpRequestInterceptor` for Spring Boot applications that logs **one structured
line per outbound HTTP exchange** made through `RestClient` or `RestTemplate`, and carries the exchange
identity in the MDC while the wire call runs. The envoy's report: the service sends a request to a
foreign party and records what came of it.

Design in brief:

- One final `ClientRequestLoggingInterceptor`, attached to every `RestClient.Builder` and
  `RestTemplateBuilder` Boot hands out; one exactly-once emission path per call.
- Injectable `NanoTimeSource` — deterministic tests, no sleeps.
- Identity per ADR-0002: a conformant `traceparent` on the outgoing request (the host's tracing
  propagation puts it there) — its trace id **is** the request id, nothing is added, the wire stays
  untouched; only a traceless call without a correlation header gets a generated `X-Correlation-Id`
  sent along via the injectable `CorrelationIdGenerator`.
- Passive bounded **tee** (`BoundedBodyCapture`) on the response body the application reads — nothing
  buffered, replayed, or withheld; the request body is the byte array the client hands the interceptor.
- SLF4J fluent API with `addKeyValue` — structured encoders pick the fields up directly.
- Boot auto-configuration + `adapter-logging.*` properties; the functional beans (interceptor, time
  source, id generator, header masker) overridable; the customizers conditional on Boot's
  `spring-boot-restclient`.
- `adapter_request_id`, `adapter_method`, `adapter_route` in the MDC for the wire call, as an additive
  overlay — an inbound request's `endpoint_*` keys (Limesium) or a bridge's trace keys stay in place.

Deliberately **out of scope**: body masking transformers, retries, and per-key response sampling.
(Logged header values are masked by default, with `unmasked` as the explicit plaintext allowlist; an
optional arrival line can announce the call before the wire; a retrying interceptor outside this one
simply yields one line per attempt.)

The long-form guide — introduction, architecture, integration into a foreign project, configuration,
metrics and the stack-specific behaviours — is [`docs/GUIDE.md`](docs/GUIDE.md).

## Usage

The host must be a **Spring Boot 4.x** application on Java 21 with an SLF4J 2.x binding, an HTTP engine
behind the request factory, and — for the automatic wiring below — Boot's `spring-boot-restclient`
module. No web application is required: a batch job that calls out is a client too. The full list with
the reasons is the guide's [prerequisites table](docs/GUIDE.md#31-prerequisites); how the `adapter_*`
fields become visible in the log output is [§3.7](docs/GUIDE.md#37-logging-backend-and-structured-output).

```xml
<dependency>
    <groupId>eu.inqudium</groupId>
    <artifactId>legatium-restclient-logging</artifactId>
    <version><!-- current release: see the badge below --></version>
</dependency>
```

[![Maven Central](https://img.shields.io/maven-central/v/eu.inqudium/legatium-restclient-logging.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/eu.inqudium/legatium-restclient-logging)
— there is no BOM; the version is declared on the dependency itself. An application may carry both
twins (a servlet host using `RestClient` for most calls and `WebClient` for a streaming one gets both
logged, in one format); keep them at the same version, both jars inline the same shared classes.
`adapter-logging.enabled=false` removes the module again without touching the classpath.

### Automatic wiring

With `spring-boot-restclient` on the classpath (it comes with `spring-boot-starter-restclient` — since
Boot 4 the web starters no longer pull it, so a host that only has `spring-boot-starter-web` must add
it) the auto-configuration registers the interceptor bean **and** two
late customizers — a `RestClientCustomizer` and a `RestTemplateCustomizer` — that append it to every
client Boot builds, and thereby to every HTTP service client group built from Boot's builder. The long
form is the guide's [§3.3](docs/GUIDE.md#33-automatic-wiring).

The hooks are Boot's **builder Spring beans**: the `RestClient.Builder` bean (prototype-scoped, one fresh
builder per injection point) and the `RestTemplateBuilder` bean, both defined by Boot's
`spring-boot-restclient` auto-configurations, which apply every customizer before handing a builder
out. Only clients built from those beans carry the interceptor. Every adapter must therefore obtain its
client from the **injected** builder, never from `RestClient.create(...)`, the static
`RestClient.builder()` or a `RestTemplate()` constructed directly — those bypass Boot's customizers and
log nothing (see manual wiring below).

```kotlin
@Service
class ThingsAdapter(builder: RestClient.Builder) {       // Boot's RestClient.Builder bean, injected
    private val client = builder.baseUrl("https://api.example.com").build()
}

@Service
class LegacyThingsAdapter(builder: RestTemplateBuilder) { // Boot's RestTemplateBuilder bean, injected
    private val template = builder.rootUri("https://api.example.com").build()
}
```

The customizers are ordered late (`Ordered.LOWEST_PRECEDENCE - 10`), so the interceptor runs inside the
interceptors of earlier customizers, closest to the wire: an authentication interceptor has already
added its header, a retrying interceptor invokes it once per attempt
([§3.5](docs/GUIDE.md#35-interceptor-order-and-other-interceptors)).

### Manual wiring

The interceptor bean `ClientRequestLoggingInterceptor` exists in every enabled context; only its
*attachment* depends on Boot's builders. Attach it yourself when a client does not pass through them
(the long form — including the switch-safe `ObjectProvider` variant and construction outside a Spring
context — is the guide's [§3.4](docs/GUIDE.md#34-manual-wiring)):

- **The host wires its clients by hand** — `RestClient.create(...)`, the static `RestClient.builder()`,
  or a `RestTemplate` constructed directly instead of through the injected builders. Boot's customizers
  never see such a client, so the interceptor is not on it.
- **`spring-boot-restclient` is absent** — a host that depends on `spring-web` directly, without a Boot
  starter for the client. The nested customizer configurations are conditional on that module and back
  off silently; the interceptor bean stays.
- **A builder obtained from Boot is customised after the customizers ran** — interceptors the host adds
  directly on the builder run *inside* this one, outside the ordering guarantee above. Where the logging
  interceptor must stay innermost, add it last by hand instead.

In each case inject the bean and append it as the **last** interceptor, so it sits closest to the wire:

```kotlin
@Configuration(proxyBeanMethods = false)
class ThingsClientConfiguration {
    @Bean
    fun thingsClient(loggingInterceptor: ClientRequestLoggingInterceptor): RestClient =
        RestClient.builder()
            .baseUrl("https://api.example.com")
            .requestInterceptor(authenticationInterceptor)
            .requestInterceptor(loggingInterceptor)
            .build()

    @Bean
    fun legacyTemplate(loggingInterceptor: ClientRequestLoggingInterceptor): RestTemplate =
        RestTemplate().apply { interceptors = interceptors + loggingInterceptor }
}
```

Reuse the one bean rather than constructing a second interceptor: the meters are identified by name,
so all interceptors on one `MeterRegistry` share one metrics owner and the
`adapter.logging.exchanges.open` gauge reports the total across them
([§6.9](docs/GUIDE.md#69-one-metrics-instance-per-registry)). Replacing the interceptor itself (a
host-defined `ClientRequestLoggingInterceptor` bean) is a different thing: the automatic wiring still
attaches the replacement ([§3.6](docs/GUIDE.md#36-overriding-beans)).

### The exchange line

Example line (on the `http-adapter-exchange` logger):

```
Adapter http exchange GET https://api.example.com/things/42 -> 200 [adapter_request_id=0f7c...]
```

plus the structured `adapter_*` key-values: the wire names are a contract with the log index, each field
owns its JSON shape (`ClientLogFields.kt`, one enum for both twins in `legatium-common`), a badly typed
value drops that field with a warning but never the event, and the request id rides the MDC (plus the
message suffix for plain-text appenders) rather than a key-value. The index-side mapping ships as a
component template in [`/docs/elk/`](../docs/elk/README.md), kept in lockstep with the enum by
`ClientLogFieldTest` in `legatium-common`.

| Field | Shape | When |
|---|---|---|
| `adapter_outcome` | keyword | always — `success` / `failure` / `timeout`; decoupled from the level |
| `adapter_duration_ms` | long | always — until the response is closed, i.e. including the body read |
| `adapter_request_method` | keyword | always |
| `adapter_response_status_code` | short | when a response arrived — absent for a refused connection or a timeout before the status line (`-> -`) |
| `adapter_url_host` | keyword | when the URI has a host — `host` or `host:port`, the outbound coordinate |
| `adapter_url_path` | keyword | always — raw path, high cardinality |
| `adapter_url_template` | keyword | when `RestClient.uri(String, ...)` recorded a template — the aggregation half; never for `RestTemplate` |
| `adapter_url_query` | keyword | when the request carried one and query logging is on |
| `adapter_slow` | boolean | only when the slow threshold was reached |
| `adapter_request_headers` / `adapter_response_headers` | keyword, display-only | when selected headers are present |
| `adapter_request_body` / `adapter_response_body` | keyword, display-only | when body capture is on and bytes actually flowed |

## Configuration (`adapter-logging.*`)

A complete, commented reference configuration with every property at its default lives in
[`/docs/adapter-logging-reference.yml`](../docs/adapter-logging-reference.yml) — copy the block and change
only what you need. `ClientLoggingReferenceConfigTest` in `legatium-common` keeps it in lockstep with
the shared `ClientLoggingProperties` class both twins bind: every key must exist, every value must be
the built-in default.

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` removes the interceptor and its customizers (auto-configuration backs off entirely) |
| `logger-name` | `http-adapter-exchange` | Logger of the exchange lines (dedicated name, distinct from Limesium's `http-exchange`, so the two streams route independently) |
| `correlation-id-header` | `X-Correlation-Id` | Header read from a traceless request, or generated and SENT on one without it (ADR-0002) |
| `include-query-string` | `true` | Log the query string as its own field |
| `log-request-start` | `false` | Additionally log an arrival line before the wire call — it carries no outcome/status/duration, so outcome-keyed dashboards still count one line per call |
| `include-path-patterns` | *(empty)* | URL patterns (Spring `PathPattern`, e.g. `/api/**`) the interceptor is active for at all, matched on the request path whatever the host; empty = every call. A call is logged when it matches any include and no exclude — the exclude wins |
| `exclude-path-prefixes` | *(empty)* | Request-path prefixes that are not logged at all |
| `exclude-hosts` | *(empty)* | Peer hosts (case-insensitive, without port) that are not logged at all — a metrics gateway, a config server |
| `slow-request-threshold` | `5s` | At/above this duration the line escalates to WARN and is flagged `slow` |
| `request-headers.*` / `response-headers.*` | `masked: ["*"]`, the rest empty | Per-direction sections with `includes` (names or `*`), `excludes`, `masked` (default `*`: every logged value is rendered by the `HeaderValueMasker` bean, a stable `length:hash` pseudonym) and `unmasked` (the explicit names allowed in plaintext, no wildcard). Masked by default, so `includes: ["*"]` costs readability, not confidentiality (ADR-0005) |
| `log-request-body` / `log-response-body` | `never` | `never`, `on-failure` or `always` (ADR-0006). Bodies are captured by a bounded tee (the request body as handed over; the response body as the application reads it); `on-failure` writes them only when `adapter_outcome` is not `success` or the status is a 4xx — the volume switch: the request body is buffered before the outcome is known and dropped on success |
| `max-body-bytes` | `16384` | Capture limit per body; beyond it the log truncates (and says so), the exchange is untouched |
| `measure-request-body-size` / `measure-response-body-size` | `false` | Count body bytes for the size meters (`adapter.request/response.body.size`) and the response read-state counter without logging content |
| `masking-key` | *(empty)* | Keys the masking fingerprint (HMAC-SHA256): same shape and stability, guess-proof without the key. A secret — supply it as one; empty keeps the unkeyed fingerprint |

Levels carry severity only (`adapter_outcome` carries the semantic): ERROR when the call threw (no
response, or the body read failed — the exception is rethrown unchanged), WARN for a timeout (its own
outcome), a 5xx answer, or a slow-but-successful call, INFO otherwise.

## Emission point

The event is emitted when the response is **closed** — which `RestClient` and `RestTemplate` do in a
`finally` after their converters read the body — so the logged status, headers and body are final and
`adapter_duration_ms` measures until the application was done with the answer: response occupancy
including the body read, not bare round-trip time. A call that produces no response emits right away
with `-> -` and no status. A response the application never closes (a raw `exchange(..., close =
false)`) stays open on the `adapter.logging.exchanges.open` gauge — the liveness signal — rather than
logging a guess.

When the call throws, a short WARN breadcrumb is additionally logged on the module's own logger (not
the exchange logger — its one-event-per-call contract holds), so the failure is visible with its cause
the moment it happens.

**Trace integration:** the outgoing W3C `traceparent` header is parsed at interceptor entry (strict W3C
validation, shared with the WebClient twin and with Limesium) and restored around the emission, so the
exchange event stays joinable with its trace: as MDC fields for structured encoders (`traceId`, and the
local client span the peer will see as its parent as `spanId`), and inline in the message
(`… [adapter_request_id=… traceId=… spanId=…]`) for plain-text appenders. The header is injected by the
client observation Boot registers, BEFORE any interceptor runs — pinned beside a real Brave bridge by
the tracing integration test. Without a conformant header, nothing is decorated and the request id is
the accepted or generated correlation id (ADR-0002).

## Metrics

Six meters, all fed from the host's `MeterRegistry` when one exists (actuator); without one a private
registry absorbs the values and the module works unchanged. Rates, latencies and status distributions are
deliberately left to Boot's own `http.client.requests` and to the structured log fields.

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `adapter.logging.failopen` | counter | `stage` = `emission` \| `arrival` \| `wiring` | Logging failures the fail-open path swallowed: `emission` = an exchange event was **lost**, `arrival` = a start line was lost, `wiring` = wiring or bookkeeping around the call failed - a pre-call wiring failure degrades the interceptor to an unlogged pass-through, a post-call one usually still emits the event. A lost log line cannot reliably report itself through the same pipeline — this counter is the independent channel. |
| `adapter.logging.events` | counter | `outcome` | Exchange events actually **emitted** (after the level gate; arrival lines excluded). The reconciliation ground truth: compare its sum against the count of indexed events — any difference is loss in the log pipeline itself. |
| `adapter.request.body.size` / `adapter.response.body.size` | distribution summary (bytes) | `uri` (template, `UNKNOWN` without one), `host` | Bytes that **actually flowed**, opt-in via `measure-request-body-size` / `measure-response-body-size` and independent of body logging and log level. Exact beyond `max-body-bytes`; zero-byte bodies record no sample. |
| `adapter.response.body.read` | counter | `uri`, `host`, `state` = `unread` \| `partial` \| `complete` | How far the application **consumed** the response body, opt-in via `measure-response-body-size`. The tee mirrors consumption, not transmission, so neither the logged body nor the size sample can tell a body the peer sent but the application dropped (`toBodilessEntity`, an error status whose body was ignored) from one that was never sent — this counter can. |
| `adapter.logging.exchanges.open` | gauge | — | Exchanges between interceptor entry and response close. Hovers near the in-flight call count in health; a **monotonically growing baseline** means responses are not being closed and events are lost silently — the one failure mode neither the fail-open counter (nothing throws) nor the events counter (no baseline) can see. |
| `adapter.logging.correlation.id` | counter | `source` = `trace` \| `header` \| `generated` | Origin of each call's request id (ADR-0002). A rising `generated` share means the application stopped propagating `traceparent` (or a correlation header) onto its outbound calls — in a host with tracing configured it reads zero by construction. |

## The WebClient twin and the shared layer

[`legatium-webclient-logging`](../legatium-webclient-logging/README.md) is the WebClient twin of this
module: identical message format, field family, `adapter-logging.*` configuration and meters, so that a
dashboard or index mapping never cares which client produced an event. This module is the **reference
implementation** — the configuration reference (`/docs/adapter-logging-reference.yml`) and the ELK
component template (`/docs/elk/`) live in the repository-shared `/docs` and are bound by both modules'
lockstep tests.

The **byte-identical** part of the shared layer (the `traceparent` parser with its fuzz target, the
injectable time/id interfaces, the fail-open helpers, the MDC keys and scope, the header sections with
the masking fingerprint, the timeout classification, the `adapter_*` field enum and the
`adapter-logging.*` properties class) lives in the internal `legatium-common` module and
is **inlined into this jar** by the Maven Shade plugin
([ADR-0003](../docs/adr/ADR-0003-legatium-common-inlined-by-shade.md)): consumers add exactly one
artifact, the published POM carries no extra dependency, and `legatium-common` itself is never
published.

Everything whose twin copies genuinely differ (metrics with their per-stack outcome vocabulary,
emitters, exchanges, interceptor vs. filter, body capture) stays **deliberately duplicated**: one twin per client, standalone jars, contract-level code that changes rarely. For that
remainder every change is a conscious port in *both* directions; the pins in `TwinContractTest` and the
cross-module tests catch *named* contract drift, not behavioural drift.

## Overriding

Define your own bean to replace a default: `NanoTimeSource`, `CorrelationIdGenerator`,
`HeaderValueMasker` (how masked header values render — a keyed HMAC, a fixed `***`), or a complete
`ClientRequestLoggingInterceptor`. The shared types a custom bean touches — `ClientLoggingProperties`,
`NanoTimeSource`, `CorrelationIdGenerator`, `HeaderValueMasker` — live in the package
`eu.inqudium.legatium.common`. A custom interceptor bean takes over the *interceptor*, not the
wiring: the auto-configured `RestClientCustomizer` and `RestTemplateCustomizer` still attach it to every
Boot-built client. A host that builds its clients by hand adds the bean itself — see
[manual wiring](#manual-wiring). Set `adapter-logging.enabled=false` to remove everything.
