# legatium-webclient-logging

The **WebClient twin of [`legatium-restclient-logging`](../legatium-restclient-logging/README.md)**: an
auto-configured `ExchangeFilterFunction` that logs one structured `adapter_*` line per outbound HTTP
exchange — with the **identical message format, identical field family, identical `adapter-logging.*`
configuration and the identical meters**. A dashboard, alert, or index mapping must not care which
client produced an event.

The long-form guide — introduction, architecture, integration into a foreign project, configuration,
metrics and the stack-specific behaviours — is [`docs/GUIDE.md`](docs/GUIDE.md).

The RestClient module is the reference implementation; its documentation applies here too:

- **Configuration:** the one complete commented reference for both twins is the repository-shared
  [`/docs/adapter-logging-reference.yml`](../docs/adapter-logging-reference.yml) — bound by
  `ClientLoggingReferenceConfigTest` in `legatium-common` against the one `ClientLoggingProperties`
  class both twins inline, so the namespace cannot drift from the code, and the twins cannot drift
  from each other by construction. The properties are explained in the guide's
  [§4](docs/GUIDE.md#4-configuration).
- **Index mapping:** the one component template for both stacks is the repository-shared
  [`/docs/elk/`](../docs/elk/README.md) — bound by `ClientLogFieldTest` in `legatium-common` against
  the one `ClientLogField` enum both twins inline. The field table is the guide's
  [§5.1](docs/GUIDE.md#51-log-fields).
- **Metrics:** the same six meters (`adapter.logging.failopen`, `adapter.logging.events`,
  `adapter.logging.exchanges.open`, `adapter.logging.correlation.id`, `adapter.request/response.body.size`,
  `adapter.response.body.read`), consumed from the host's `MeterRegistry`, never exported. The meter
  table is the guide's [§5.4](docs/GUIDE.md#54-meters).

## Deliberate stack differences

| Concern | RestClient twin | This module |
|---|---|---|
| Disposition vocabulary | `success` / `failure` / `timeout` | plus **`cancelled`** — a cancelled subscription (a downstream `timeout()` operator, a `take`, a disposed caller) is the reactive reality a blocking call cannot have. Note: a `Mono.timeout()` the *caller* applies logs `cancelled`; a timeout the *connector* raises (Reactor Netty's `responseTimeout`) logs `timeout` |
| Emission point | response **close** | the response **body's terminal signal** (complete, error or cancel) — the moment the exchange is truly over; a call without a response emits at the response `Mono`'s error/cancel signal with `-> -` |
| Never-completing exchange | a response the application never closes stays open on the gauge | a response body nobody subscribes to or releases stays open on the gauge — every `retrieve`/`exchangeToMono`/`exchangeToFlux` path of `WebClient` subscribes or releases; a raw `exchange()` caller owns that duty |
| Request body | the byte array the client hands the interceptor | teed at the connector's `writeWith` as the caller's `BodyInserter` writes it — the request is rebuilt with a wrapping inserter; a bodiless request stays untouched |
| Call-wide MDC | thread-local `MdcScope` around the wire call | **none** — the call hops event-loop threads; the identity rides the emission's `MdcScope` (owned trace keys, additive overlay) and the message inline; propagating it into reactive operators is the host's context-propagation business |
| Read failure mid-body | `IOException` from the tee stream, reported and rethrown | the body `Flux`'s error signal — `failure` with the received status |
| Attachment | `RestClientCustomizer` + `RestTemplateCustomizer` | `WebClientCustomizer` (`builder.filter(...)`, late, so the filter runs inside the filters of earlier customizers — closest to the connector) |
| Body tee concurrency | volatile single-writer capture | lock-guarded, **frozen at emission**: a body chunk still in flight after a cancellation cannot move the logged text or the size sample |

Everything else — fail-open including the wiring (`stage=wiring` degrades to pass-through), the
level/outcome decoupling, slow escalation, header sections with `includes`/`excludes`/`masked`/`unmasked`
(masked by default, ADR-0005) and the injectable `HeaderValueMasker` (default: the stable fingerprint),
the arrival line (`log-request-start`), count-only body measuring, activation
by host and path, the identity contract of ADR-0002 (trace id is the request id; the correlation header
is sent only on traceless calls without one) — behaves exactly as documented in the RestClient twin's
README and guide.

## The shared layer

The **byte-identical** part of the twins' shared layer (the `traceparent` parser with its fuzz target,
the injectable time/id interfaces, the fail-open helpers, the MDC keys and scope, the header sections
with the masking fingerprint, the timeout classification, the `adapter_*` field enum and the
`adapter-logging.*` properties class) lives in the internal `legatium-common` module and is **inlined
into this jar** by the Maven Shade plugin
([ADR-0003](../docs/adr/ADR-0003-legatium-common-inlined-by-shade.md)): consumers add exactly one
artifact, the published POM carries no extra dependency, and `legatium-common` itself is never
published.

Everything whose twin copies genuinely differ (metrics with their per-stack outcome vocabulary,
emitters, exchanges, filter vs. interceptor, body capture with its own concurrency design)
stays **deliberately duplicated**: one twin per client, standalone jars, contract-level code that changes
rarely. For that remainder every change is a conscious port in both directions; the pins in
`TwinContractTest` (and, in `legatium-common`, `ClientLogFieldTest` / `ClientLoggingReferenceConfigTest`) catch *named* contract
drift (meter names, field names, configuration keys, message text) — not behavioural drift inside
near-identical code.

## Usage

The host must be a **Spring Boot 4.x** application on Java 21 with an SLF4J 2.x binding, a connector,
and — for the automatic wiring below — Boot's `spring-boot-webclient` module. The full list with the
reasons is the guide's [prerequisites table](docs/GUIDE.md#31-prerequisites); how the `adapter_*`
fields become visible in the log output is [§3.7](docs/GUIDE.md#37-logging-backend-and-structured-output).

```xml
<dependency>
    <groupId>eu.inqudium</groupId>
    <artifactId>legatium-webclient-logging</artifactId>
    <version><!-- current release: see the badge below --></version>
</dependency>
```

[![Maven Central](https://img.shields.io/maven-central/v/eu.inqudium/legatium-webclient-logging.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/eu.inqudium/legatium-webclient-logging)
— there is no BOM; the version is declared on the dependency itself. An application may carry both
twins (a servlet host using `RestClient` for most calls and `WebClient` for a streaming one gets both
logged, in one format); keep them at the same version, both jars inline the same shared classes.
`adapter-logging.enabled=false` removes the module again without touching the classpath.

### Automatic wiring

The long form is the guide's [§3.3](docs/GUIDE.md#33-automatic-wiring).

With `spring-boot-webclient` on the classpath (it comes with `spring-boot-starter-webclient` and
`spring-boot-starter-webflux`) the auto-configuration registers the filter bean **and** a late
`WebClientCustomizer` that appends it to every `WebClient.Builder` Boot hands out — and thereby to every
HTTP service client group built from one.

The hook is Boot's **`WebClient.Builder` Spring bean**: Boot's `WebClientAutoConfiguration` defines it
(prototype-scoped, one fresh builder per injection point) and applies every `WebClientCustomizer` to it,
this module's included. Only clients built from that bean carry the filter. Every adapter must therefore
obtain its client from the **injected** builder, never from `WebClient.create(...)` or the static
`WebClient.builder()` — those bypass Boot's customizers and log nothing (see manual wiring below).

```kotlin
@Service
class ThingsAdapter(builder: WebClient.Builder) {        // Boot's WebClient.Builder bean, injected
    private val client = builder.baseUrl("https://api.example.com").build()
}
```

The customizer is ordered late (`Ordered.LOWEST_PRECEDENCE - 10`), so the filter runs inside the filters
of earlier customizers, closest to the connector: an authentication filter has already added its
header, a retrying filter invokes it once per attempt
([§3.5](docs/GUIDE.md#35-filter-order-and-other-filters)).

### Manual wiring

The long form — including the switch-safe `ObjectProvider` variant and construction outside a Spring
context — is the guide's [§3.4](docs/GUIDE.md#34-manual-wiring).

The filter bean `ClientRequestLoggingFilter` exists in every case; only its *attachment* depends on Boot.
Attach it yourself when a client does not pass through Boot's builder:

- **The host wires its clients by hand** — `WebClient.create(...)` or the static `WebClient.builder()`
  instead of the injected `WebClient.Builder`. Boot's customizers never see such a client, so the filter
  is not on it.
- **`spring-boot-webclient` is absent** — a host that depends on `spring-webflux` directly, without a
  Boot starter for the client. The nested customizer configuration is conditional on that module and
  backs off silently; the filter bean stays.
- **A builder obtained from Boot is customised after the customizers ran** — filters the host adds
  directly on the builder run *inside* this one, outside the ordering guarantee above. Where the
  logging filter must stay innermost, add it last by hand instead.

In each case inject the bean and append it as the **last** filter, so it sits closest to the connector:

```kotlin
@Configuration(proxyBeanMethods = false)
class ThingsClientConfiguration {
    @Bean
    fun thingsClient(loggingFilter: ClientRequestLoggingFilter): WebClient =
        WebClient.builder()
            .baseUrl("https://api.example.com")
            .filter(authenticationFilter)
            .filter(loggingFilter)
            .build()
}
```

Reuse the one bean rather than constructing a second filter: the meters are identified by name, so all
filters on one `MeterRegistry` share one metrics owner and the `adapter.logging.exchanges.open` gauge
reports the total across them ([§6.9](docs/GUIDE.md#69-one-metrics-instance-per-registry)). Replacing
the filter itself (a host-defined `ClientRequestLoggingFilter` bean) is a different thing: the
automatic wiring still attaches the replacement ([§3.6](docs/GUIDE.md#36-overriding-beans)).

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
  "process": { "pid": 4711, "thread": { "name": "reactor-http-epoll-2" } },
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
response shows `-> -` and no status field; a cancelled one `adapter_outcome=cancelled`. Optional fields
(`adapter_url_query`, `adapter_slow`, the header and body sections) are present only when they apply.
Which encoder produces which shape — and why the default console pattern shows none of the fields —
is the guide's [§3.7](docs/GUIDE.md#37-logging-backend-and-structured-output); the field family itself is
documented once, in the guide's [§5.1](docs/GUIDE.md#51-log-fields), and mapped by the component template
in [`/docs/elk/`](../docs/elk/README.md).

## Configuration (`adapter-logging.*`)

Every property lives under the `adapter-logging.*` namespace, identical in both twins by construction:
both bind the one shared `ClientLoggingProperties` class. The complete, commented reference with every
key at its default is the repository-shared
[`/docs/adapter-logging-reference.yml`](../docs/adapter-logging-reference.yml) — copy the block and
change only what you need; `ClientLoggingReferenceConfigTest` in `legatium-common` fails the build on
any drift between that file and the class. The properties are explained in the guide's
[§4](docs/GUIDE.md#4-configuration): the property reference, header sections, body logging and
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

Every meter with its type, tags and meaning is the guide's [§5.4](docs/GUIDE.md#54-meters); how to read
them together, with a suggested alert set, is [§5.5](docs/GUIDE.md#55-reading-the-meters-together). The
names are identical in both twins and pinned by `TwinContractTest`; the `outcome` tag of the events
counter carries this stack's `cancelled` in addition.

## Coroutines

`WebClient` is the one reactive client; Kotlin coroutine callers use it through `awaitBody`,
`awaitExchange` and friends on the same filter chain, so there is no separate coroutine variant and no
`variant` key: the filter sees the same `ClientRequest`/`ClientResponse` either way, and the body
terminal signal that drives the emission is the same `Flux` completion the coroutine bridge awaits.
