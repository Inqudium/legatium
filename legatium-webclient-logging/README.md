# legatium-webclient-logging

The **WebClient twin of [`legatium-restclient-logging`](../legatium-restclient-logging/README.md)**: an
auto-configured `ExchangeFilterFunction` that logs one structured `client_*` line per outbound HTTP
exchange — with the **identical message format, identical field family, identical `client-logging.*`
configuration and the identical meters**. A dashboard, alert, or index mapping must not care which
client produced an event.

The long-form guide — introduction, architecture, integration into a foreign project, configuration,
metrics and the stack-specific behaviours — is [`docs/GUIDE.md`](docs/GUIDE.md).

The RestClient module is the reference implementation; its documentation applies here too:

- **Configuration:** the one complete commented reference for both twins is the repository-shared
  [`/docs/client-logging-reference.yml`](../docs/client-logging-reference.yml) — bound by
  `ClientLoggingReferenceConfigTest` in `legatium-common` against the one `ClientLoggingProperties`
  class both twins inline, so the namespace cannot drift from the code, and the twins cannot drift
  from each other by construction.
- **Index mapping:** the one component template for both stacks is the repository-shared
  [`/docs/elk/`](../docs/elk/README.md) — bound by `ClientLogFieldTest` in `legatium-common` against
  the one `ClientLogField` enum both twins inline.
- **Metrics:** the same six meters (`client.logging.failopen`, `client.logging.events`,
  `client.logging.exchanges.open`, `client.logging.correlation.id`, `client.request/response.body.size`,
  `client.response.body.read`), consumed from the host's `MeterRegistry`, never exported.

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
level/outcome decoupling, slow escalation, header sections with `includes`/`excludes`/`masked` and the
stable masking fingerprint, the arrival line (`log-request-start`), count-only body measuring, activation
by host and path, the identity contract of ADR-0002 (trace id is the request id; the correlation header
is sent only on traceless calls without one) — behaves exactly as documented in the RestClient twin's
README and guide.

## The shared layer

The **byte-identical** part of the twins' shared layer (the `traceparent` parser with its fuzz target,
the injectable time/id interfaces, the fail-open helpers, the MDC keys and scope, the header sections
with the masking fingerprint, the timeout classification, the `client_*` field enum and the
`client-logging.*` properties class) lives in the internal `legatium-common` module and is **inlined
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

```xml
<dependency>
    <groupId>eu.inqudium</groupId>
    <artifactId>legatium-webclient-logging</artifactId>
</dependency>
```

Auto-configures in ANY Spring Boot application that has `WebClient` on the classpath — servlet,
reactive, or none — and attaches the filter to every `WebClient.Builder` Boot hands out (and to every
HTTP service client group built from one). An application may carry both twins: a servlet host that
uses `RestClient` for most calls and `WebClient` for a streaming one gets both logged, in one format.
Keep the two versions equal (both jars inline the same shared classes).

## Coroutines

`WebClient` is the one reactive client; Kotlin coroutine callers use it through `awaitBody`,
`awaitExchange` and friends on the same filter chain, so there is no separate coroutine variant and no
`variant` key: the filter sees the same `ClientRequest`/`ClientResponse` either way, and the body
terminal signal that drives the emission is the same `Flux` completion the coroutine bridge awaits.
