# legatium-restclient-logging

The **RestClient/RestTemplate twin of [`legatium-webclient-logging`](../legatium-webclient-logging/README.md)**
and the **reference implementation** of Legatium: an auto-configured `ClientHttpRequestInterceptor` that
logs one structured `adapter_*` line per outbound HTTP exchange made through `RestClient` or
`RestTemplate` — the envoy's report: the service sends a request to a foreign party and records what
came of it — and carries the exchange identity in the MDC while the wire call runs. Message format,
field family, `adapter-logging.*` configuration and meters are **identical** in both twins: a dashboard,
alert, or index mapping must not care which client produced an event.

The long-form guide — introduction, architecture, integration into a foreign project, configuration,
metrics and the stack-specific behaviours — is [`docs/GUIDE.md`](docs/GUIDE.md); what the module does
and deliberately does not do (no body masking transformers, no retries, no sampling) is its
[§1.1](docs/GUIDE.md#11-what-the-module-does) and [§1.2](docs/GUIDE.md#12-what-the-module-deliberately-does-not-do).

This module is the reference implementation; the documentation shared by both twins is bound to the
code it inlines:

- **Configuration:** the one complete commented reference for both twins is the repository-shared
  [`/docs/adapter-logging-reference.yml`](../docs/adapter-logging-reference.yml) — bound by
  `ClientLoggingReferenceConfigTest` in `legatium-common` against the one `ClientLoggingProperties`
  class both twins inline, so the namespace cannot drift from the code, and the twins cannot drift
  from each other by construction. The properties are explained in the guide's
  [Legatium guide §6](../docs/GUIDE.md#6-configuration).
- **Index mapping:** the one component template for both stacks is the repository-shared
  [`/docs/elk/`](../docs/elk/README.md) — bound by `ClientLogFieldTest` in `legatium-common` against
  the one `ClientLogField` enum both twins inline. The field table is the guide's
  [Legatium guide §7.1](../docs/GUIDE.md#71-log-fields).
- **Metrics:** the same six meters (`adapter.logging.failopen`, `adapter.logging.events`,
  `adapter.logging.exchanges.open`, `adapter.logging.correlation.id`, `adapter.request/response.body.size`,
  `adapter.response.body.read`), consumed from the host's `MeterRegistry`, never exported. The meter
  table is the guide's [Legatium guide §7.4](../docs/GUIDE.md#74-meters).

## Deliberate stack differences

| Concern | This module | WebClient twin |
|---|---|---|
| Disposition vocabulary | `success` / `failure` / `timeout` | plus **`cancelled`** — a subscription the caller abandoned is the reactive reality a blocking call cannot have; a consumer that stops reading the body because it has read enough is `success`, partially read |
| Emission point | response **close** — which `RestClient` and `RestTemplate` do in a `finally` after their converters read the body, so status, headers and body are final and `adapter_duration_ms` is **response occupancy** including the body read, not bare round-trip time; a call without a response emits right away with `-> -` | the response **body's terminal signal** (complete, error or cancel) |
| Never-completing exchange | a response the application never closes (a raw `exchange(..., close = false)`) stays open on the `adapter.logging.exchanges.open` gauge — the liveness signal — rather than logging a guess | a response body nobody subscribes to or releases stays open on the gauge |
| Request body | the byte array the client hands the interceptor — complete, captured at wiring | teed at the connector's `writeWith` as the caller's `BodyInserter` writes it |
| Call-wide MDC | thread-local `MdcScope` around the wire call: `adapter_request_id`, `adapter_method`, `adapter_route` as an **additive overlay** — an inbound request's `endpoint_*` keys (Limesium) or a bridge's trace keys stay in place | **none** — the call hops event-loop threads; the identity rides the emission's `MdcScope` and the message inline |
| Read failure mid-body | `IOException` from the tee stream, reported and rethrown unchanged | the body `Flux`'s error signal |
| URI template | recorded by `RestClient.uri(String, ...)`; **never** by `RestTemplate` | recorded by `WebClient` |
| Attachment | `RestClientCustomizer` + `RestTemplateCustomizer` (`builder.requestInterceptor(...)`, late, so the interceptor runs inside the interceptors of earlier customizers — closest to the wire) | `WebClientCustomizer` |
| Body tee concurrency | volatile single-writer capture — one thread reads the response stream | lock-guarded, frozen at emission |

Everything else — fail-open including the wiring (`stage=wiring` degrades to pass-through), the
level/outcome decoupling, slow escalation, header sections with `includes`/`excludes`/`masked`/`unmasked`
(masked by default, ADR-0005) and the injectable `HeaderValueMasker` (default: the stable fingerprint),
the arrival line (`log-request-start`), count-only body measuring, activation by host and path, the
identity contract of ADR-0002 (a conformant `traceparent` on the outgoing request — the host's tracing
propagation puts it there — makes its trace id the request id and the wire stays untouched; only a
traceless call without a correlation header gets a generated `X-Correlation-Id` sent along) — is the one
contract both twins ship, documented here and in this module's guide.

## The shared layer

The **byte-identical** part of the twins' shared layer (the `traceparent` parser with its fuzz target,
the injectable time/id interfaces, the fail-open helpers, the MDC keys and scope, the header sections
with the masking fingerprint, the timeout classification, the `adapter_*` field enum, the
`adapter-logging.*` properties class, the metrics owner parameterised by the stack and the activation)
lives in the internal `legatium-common` module and is **inlined
into this jar** by the Maven Shade plugin
([ADR-0003](../docs/adr/ADR-0003-legatium-common-inlined-by-shade.md)): consumers add exactly one
artifact, the published POM carries no extra dependency, and `legatium-common` itself is never
published.

Everything whose twin copies genuinely differ (emitters, exchanges, interceptor vs. filter, body capture
with its own concurrency design)
stays **deliberately duplicated**: one twin per client, standalone jars, contract-level code that changes
rarely. For that remainder every change is a conscious port in both directions; the pins in
`TwinContractTest` (message text, this stack's outcomes) and, in `legatium-common`, `SharedContractTest` /
`ClientLogFieldTest` / `ClientLoggingReferenceConfigTest` catch *named* contract drift (meter names, field
names, configuration keys, message text) — not behavioural drift inside near-identical code. The shaded
jar a consumer receives is exercised by the standalone `consumer-smoke/` build in CI.

## Usage

The host must be a **Spring Boot 4.x** application on Java 21 with an SLF4J 2.x binding, an HTTP engine
behind the request factory, and — for the automatic wiring below — Boot's `spring-boot-restclient`
module. No web application is required: a batch job that calls out is a client too. The full list with
the reasons is the guide's [prerequisites table](../docs/GUIDE.md#1-prerequisites); how the `adapter_*`
fields become visible in the log output is [Legatium guide §4](../docs/GUIDE.md#4-logging-backend-and-structured-output).

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

The long form is the guide's [§3.1](docs/GUIDE.md#31-automatic-wiring).

With `spring-boot-restclient` on the classpath (it comes with `spring-boot-starter-restclient` — since
Boot 4 the web starters no longer pull it, so a host that only has `spring-boot-starter-web` must add
it) the auto-configuration registers the interceptor bean **and** two late customizers — a
`RestClientCustomizer` and a `RestTemplateCustomizer` — that append it to every client Boot builds, and
thereby to every HTTP service client group built from Boot's builder.

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
([§3.3](docs/GUIDE.md#33-interceptor-order-and-other-interceptors)).

### Manual wiring

The long form — including the switch-safe `ObjectProvider` variant and construction outside a Spring
context — is the guide's [§3.2](docs/GUIDE.md#32-manual-wiring).

The interceptor bean `ClientRequestLoggingInterceptor` exists in every case; only its *attachment*
depends on Boot's builders. Attach it yourself when a client does not pass through them:

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
([§4.9](docs/GUIDE.md#49-one-metrics-instance-per-registry)). Replacing the interceptor itself (a
host-defined `ClientRequestLoggingInterceptor` bean) is a different thing: the automatic wiring still
attaches the replacement ([Legatium guide §3](../docs/GUIDE.md#3-overriding-beans)) — as it does for the other
overridable beans, `NanoTimeSource`, `CorrelationIdGenerator` and `HeaderValueMasker` (how masked header
values render — a keyed HMAC, a fixed `***`), whose types live in the package
`eu.inqudium.legatium.common`.

### The exchange line

On the `http-adapter-exchange` logger a completed exchange is one event. In a plain-text appender only
the message shows; it repeats the gist inline for exactly that case:

```
Adapter http exchange POST https://api.example.com/things/42 -> 200 [adapter_request_id=4bf92f3577b34da6a3ce929d0e0e4736 traceId=4bf92f3577b34da6a3ce929d0e0e4736 spanId=00f067aa0ba902b7]
```

With Spring Boot's structured logging (`logging.structured.format.console=ecs`) the same event is one
JSON document: the `adapter_*` key-values and the MDC-carried identity become flat, typed top-level
fields next to the encoder's own envelope:

```json
{
  "@timestamp": "2026-09-04T13:54:58.534Z",
  "log": { "level": "INFO", "logger": "http-adapter-exchange" },
  "process": { "pid": 4711, "thread": { "name": "http-nio-8080-exec-3" } },
  "service": { "name": "things-service" },
  "message": "Adapter http exchange POST https://api.example.com/things/42 -> 200 [adapter_request_id=4bf92f3577b34da6a3ce929d0e0e4736 traceId=4bf92f3577b34da6a3ce929d0e0e4736 spanId=00f067aa0ba902b7]",
  "adapter_request_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "adapter_method": "POST",
  "adapter_route": "https://api.example.com/things/42",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "00f067aa0ba902b7",
  "adapter_outcome": "success",
  "adapter_duration_ms": 17,
  "adapter_request_method": "POST",
  "adapter_response_status_code": 200,
  "adapter_url_host": "api.example.com",
  "adapter_url_path": "/things/42",
  "adapter_url_template": "https://api.example.com/things/{id}",
  "ecs": { "version": "8.11" }
}
```

The trace keys and the trace suffix in the message appear only on a traced call; a traceless call
carries the request id alone and has sent it to the peer as `X-Correlation-Id`. A call without a
response shows `-> -` and no status field. Optional fields (`adapter_url_query`, `adapter_slow`, the
header and body sections) are present only when they apply. Which encoder produces which shape — and
why the default console pattern shows none of the fields — is the guide's
[Legatium guide §4](../docs/GUIDE.md#4-logging-backend-and-structured-output); the field family itself is documented
once, in the guide's [Legatium guide §7.1](../docs/GUIDE.md#71-log-fields), and mapped by the component template in
[`/docs/elk/`](../docs/elk/README.md).

## Configuration (`adapter-logging.*`)

Every property lives under the `adapter-logging.*` namespace, identical in both twins by construction:
both bind the one shared `ClientLoggingProperties` class. The complete, commented reference with every
key at its default is the repository-shared
[`/docs/adapter-logging-reference.yml`](../docs/adapter-logging-reference.yml) — copy the block and
change only what you need; `ClientLoggingReferenceConfigTest` in `legatium-common` fails the build on
any drift between that file and the class. The properties are explained in the guide's
[Legatium guide §6](../docs/GUIDE.md#6-configuration): the property reference, header sections, body logging and
measuring, activation by host and path, logger levels, validation at startup, and example
configurations. `adapter-logging.enabled=false` removes the module without touching the classpath.

## Metrics

The module's meters exist for one reason: a log line that was lost cannot report its own loss through
the same pipeline. Six meters, consumed from the host's `MeterRegistry` when one exists (actuator) and
never exported, form that independent channel — they answer whether exchange events are being lost
loudly (a fail-open counter by stage), lost silently (an open-exchange gauge whose baseline must return
towards zero), or lost downstream (an events counter to reconcile against the index), where each call's
identity came from (trace, header, or generated), and — opt-in — how large the bodies were and how far
the application actually read them. Rates, latencies and status distributions are deliberately left to
Boot's own `http.client.requests` and to the structured log fields.

Every meter with its type, tags and meaning is the guide's [Legatium guide §7.4](../docs/GUIDE.md#74-meters); how to read
them together, with a suggested alert set, is [Legatium guide §7.5](../docs/GUIDE.md#75-reading-the-meters-together). The
names are one shared contract, pinned once by `SharedContractTest` in `legatium-common` ([ADR-0008](../docs/adr/ADR-0008-six-meters-consumed-not-exported.md)).
