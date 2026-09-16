# legatium-restclient-logging — Guide

One structured `adapter_*` log line per outbound HTTP exchange made through Spring's `RestClient` or
`RestTemplate`, with the exchange identity in the MDC while the wire call runs. This module is the
**reference implementation** of the adapter-logging family; its WebClient twin
[`legatium-webclient-logging`](../../legatium-webclient-logging/README.md) shares the message format, the
field family, the `adapter-logging.*` configuration and the meters. The inbound counterpart of the whole
family is the sibling project [Limesium](https://github.com/Inqudium/limesium).

This guide is the long-form companion to the module [README](../README.md). It explains what the module
does, how it is built, how it is wired into a foreign application, and which behaviours are specific to
the blocking client stack. Everything that is one contract for both twins — prerequisites, the
dependency, the beans, the exchange line and the logging backend, the configuration, the fields, the
meters, the fail-open promise and the shared code — is written once, in the
[Common guide](../../docs/GUIDE.md). Everything here is derived from the code under
`src/main/kotlin/eu/inqudium/legatium/restclient/logging/`; when the two disagree, the code wins.

## Table of contents

1. [Introduction](#1-introduction)
   1. [What the module does](#11-what-the-module-does)
   2. [The WebClient twin](#12-the-webclient-twin)
2. [Architecture](#2-architecture)
   1. [Component overview](#21-component-overview)
   2. [Auto-configuration and registration](#22-auto-configuration-and-registration)
   3. [Lifecycle of one exchange](#23-lifecycle-of-one-exchange)
   4. [Emission point: response close](#24-emission-point-response-close)
   5. [The body tee](#25-the-body-tee)
   6. [MDC coverage](#26-mdc-coverage)
   7. [Fail-open contract](#27-fail-open-contract)
3. [Using it in a foreign project](#3-using-it-in-a-foreign-project)
   1. [Automatic wiring](#31-automatic-wiring)
   2. [Manual wiring](#32-manual-wiring)
   3. [Interceptor order and other interceptors](#33-interceptor-order-and-other-interceptors)
   4. [Verifying the integration](#34-verifying-the-integration)
   5. [Naming a client](#35-naming-a-client)
4. [Special characteristics](#4-special-characteristics)
   1. [Differences to the WebClient twin](#41-differences-to-the-webclient-twin)
   2. [Duration is response occupancy](#42-duration-is-response-occupancy)
   3. [A response that is never closed](#43-a-response-that-is-never-closed)
   4. [Failures on the response the client was handed](#44-failures-on-the-response-the-client-was-handed)
   5. [Timeouts and how they are recognised](#45-timeouts-and-how-they-are-recognised)
   6. [RestTemplate has no URI template](#46-resttemplate-has-no-uri-template)
   7. [Retries yield one line per attempt](#47-retries-yield-one-line-per-attempt)
5. [Appendix](#5-appendix)
   1. [File map](#51-file-map)
   2. [Related documents](#52-related-documents)

---

## 1. Introduction

### 1.1 What the module does

`legatium-restclient-logging` is a Spring Boot auto-configured `ClientHttpRequestInterceptor`, attached
through Boot's `RestClientCustomizer` and `RestTemplateCustomizer` to every client the host builds through
Boot. For every outbound HTTP exchange it:

- resolves the exchange identity per ADR-0002: a conformant `traceparent` on the outgoing request — put
  there by the host's tracing propagation — makes its trace id **the** request id and leaves the wire
  untouched; only a traceless call adopts a correlation header already on the request, or generates one
  and **sends** it, so the peer can quote it;
- puts `adapter_request_id`, `adapter_method` and `adapter_route` into the **MDC for the wire call**, as an
  additive overlay beside whatever the thread already carries (an inbound request's `endpoint_*` keys
  from Limesium, a tracing bridge's keys);
- optionally logs an **arrival line** the moment the request is sent;
- measures the exchange duration with an injectable monotonic time source — until the response is closed;
- captures the request body the client hands it (bounded) and optionally tees the response body as the
  application reads it (bounded, never buffered or replayed);
- optionally records the selected request/response headers, with stable masking of sensitive values;
- parses the outgoing W3C `traceparent` header (`traceId`/`spanId`) so the event stays joinable with its
  trace;
- emits **exactly one** structured completion event at **response close** — after the client's converters
  read the body, so status, headers, body and duration are final;
- feeds six Micrometer meters that observe the logging itself.

It does all of this **fail-open**: no failure inside the logging — wiring, body tee, MDC adapter,
emission, metrics — can ever fail, delay or alter the call it describes
([Common guide §8.2](../../docs/GUIDE.md#82-fail-open-contract)).

What the exchange line looks like — the message, the structured document, the arrival line — is
[Common guide §4](../../docs/GUIDE.md#4-logging-backend-and-structured-output); what the module
deliberately does not do (no rates or latencies as metrics, no retries, no body masking transformers, no
replaying body cache, no hand-built clients) is
[Common guide §8.1](../../docs/GUIDE.md#81-what-the-modules-deliberately-do-not-do). A hand-built
`RestClient` or `RestTemplate` gets the interceptor bean added by the host ([§3.2](#32-manual-wiring)).

### 1.2 The WebClient twin

The module is the **reference implementation** for the WebClient twin: it owns the message text and the
blocking stack's outcome vocabulary, and the cross-stack contract files — configuration reference, field
family and index mapping — live in the repository-shared `/docs`, bound by both builds. The contract and
the lockstep tests that pin it are
[Common guide §9.2](../../docs/GUIDE.md#92-the-twin-contract-and-its-lockstep-tests). A host that uses
both clients (a servlet application with a `WebClient` for streaming calls) may carry both modules, each
logging the client it serves.

---

## 2. Architecture

### 2.1 Component overview

Six Kotlin files in one package, `eu.inqudium.legatium.restclient.logging`, plus the shared layer, in
five layers:

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Auto-configuration                                                           │
│   ClientLoggingAutoConfiguration                                             │
│     ├─ RestClientCustomization   (RestClientCustomizer, late)                │
│     └─ RestTemplateCustomization (RestTemplateCustomizer, late)              │
│   ClientLoggingProperties · HeaderLogProperties (both shared)                │
├──────────────────────────────────────────────────────────────────────────────┤
│ Client lifecycle                                                             │
│   ClientRequestLoggingInterceptor (ClientHttpRequestInterceptor)             │
│     └─ CapturingClientHttpResponse (ClientHttpResponse)  ◀ emission on close │
├──────────────────────────────────────────────────────────────────────────────┤
│ State and emission                                                           │
│   Exchange                                                                   │
│   ExchangeLogEmitter  ──▶  ClientLogField (shared)                           │
│   ClientLoggingMetrics                                                       │
├──────────────────────────────────────────────────────────────────────────────┤
│ Capture                                                                      │
│   BoundedBodyCapture                                                         │
├──────────────────────────────────────────────────────────────────────────────┤
│ Cross-cutting (legatium-common, inlined)                                     │
│   ClientLogField · MdcKeys · TraceMdcKeys · MdcScope · Traceparent · Timeouts│
│   NanoTimeSource · CorrelationIdGenerator · reportQuietly · failOpen         │
└──────────────────────────────────────────────────────────────────────────────┘
```

| Class | Responsibility |
|---|---|
| `ClientLoggingAutoConfiguration` | Registers the interceptor bean, the default `NanoTimeSource` / `CorrelationIdGenerator` / `HeaderValueMasker`, and — when Boot's `spring-boot-restclient` is present — a late `RestClientCustomizer` and `RestTemplateCustomizer` that append the interceptor. |
| `ClientRequestLoggingInterceptor` | Owns the **client side**: activation by host and path, fail-open wiring, identity resolution (`traceparent` first, correlation header on traceless calls) with the traceless header, the request-body capture, the call-wide `MdcScope`, the breadcrumb, the no-response path, the handoff to the response wrapper. |
| `CapturingClientHttpResponse` | The response the client gets back: delegates, tees the body the application reads, reports a failure of **any** delegate operation (status, headers, body open/read/close, response close), and turns `close()` into the emission point. |
| `Exchange` | Per-exchange state from entry to emission; the exactly-once guards; the caller's MDC snapshot. |
| `CallerMdcSnapshot` / `CallerMdcRestorer` | The caller's MDC at wiring, own and trace keys excluded, restored around the emission only on another thread than the calling one (ADR-0011). |
| `ExchangeLogEmitter` | Builds and emits the arrival line and the completion event; resolves level, outcome and cause (timeouts via the shared `Timeouts`); records body sizes; restores the caller's MDC snapshot for a close on another thread, then opens the emission `MdcScope` with trace ownership. |
| `BoundedBodyCapture` | The bounded capture target; count-only mode with limit `0`; the response-side read state (`BodyReadState`); single-writer/late-reader visibility via a volatile total. |
| Shared layer (`legatium-common`, inlined) | `ClientLoggingProperties` / `HeaderLogProperties`, `ClientLogField`, `ClientLoggingMetrics`, `ClientActivation`, `MdcScope`, `Traceparent`, `Timeouts`, the injectable collaborators and the fail-open guards — one implementation for both twins, class by class in [Common guide §9.1](../../docs/GUIDE.md#91-the-shared-classes). |

### 2.2 Auto-configuration and registration

`ClientLoggingAutoConfiguration` is listed in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` and is conditional on
`adapter-logging.enabled` (default `true`) only — no web application type: a batch job that calls out is a
client too. It registers:

| Bean | Condition | Purpose |
|---|---|---|
| `NanoTimeSource` | `@ConditionalOnMissingBean` | `NanoTimeSource.SYSTEM` |
| `CorrelationIdGenerator` | `@ConditionalOnMissingBean` | `CorrelationIdGenerator.DEFAULT` (counting generator: random per-instance base-36 prefix + counter, 21 chars — ADR-0004) |
| `HeaderValueMasker` | `@ConditionalOnMissingBean` | `HeaderValueMasker.forKey(properties.maskingKey)` — the `length:hash` fingerprint, HMAC-keyed when `masking-key` is set; the one bean both twins mask with (the interceptor's constructor defaults to the same, so manual wiring honours the key too) |
| `ClientRequestLoggingInterceptor` | `@ConditionalOnMissingBean` | the interceptor, built from the bound properties and the host's `MeterRegistry` (`ObjectProvider`; an empty, no-op `CompositeMeterRegistry` without one) |
| `RestClientCustomizer` | `@ConditionalOnClass(RestClientCustomizer)`, `@Order(LOWEST_PRECEDENCE - 10)` | `builder.requestInterceptor(interceptor)` on every `RestClient.Builder` Boot hands out |
| `RestTemplateCustomizer` | `@ConditionalOnClass(RestTemplateCustomizer)`, same order | appends the interceptor to every `RestTemplate` built through `RestTemplateBuilder` |

Because the interceptor is its own bean, a host can replace it while keeping the customizers
([Common guide §3](../../docs/GUIDE.md#3-overriding-beans)). Boot's `spring-boot-restclient` module is an **optional** dependency:
without it the interceptor bean still exists and the host attaches it by hand ([§3.2](#32-manual-wiring)).

### 2.3 Lifecycle of one exchange

```
RestClient.retrieve()/exchange()  or  RestTemplate.execute()
   │  (client observation opened; traceparent injected by the host's propagation)
   ▼
InterceptingClientHttpRequest ──▶ [earlier interceptors] ──▶ ClientRequestLoggingInterceptor.intercept
                       │
                       ├─ shouldNotFilter(uri)?  ──yes──▶ execution.execute   (untouched pass-through)
                       │
                       ├─ wireExchange  ──throws──▶ execution.execute   (fail-open, stage=wiring)
                       │     • request id: traceparent trace id, else header on the request, else generated
                       │       and ADDED to the request (ADR-0002)
                       │     • request body captured from the byte array (bounded / count-only)
                       │     • response capture created if logging OR measuring is on
                       │     • request headers selected and masked (multi-value, comma-joined, AFTER the
                       │       correlation header was added)
                       │     • traceId/spanId parsed from the traceparent header
                       │     • startNanos read from NanoTimeSource; gauge exchanges.open += 1
                       │
                       ├─ MdcScope(requestId, method, target) opened   (fail-open: no scope on failure)
                       ├─ logRequestStart if enabled
                       │
                       └─ try     response = execution.execute(request, body)        ← the wire call
                                  return CapturingClientHttpResponse(response, capture, hooks)
                          catch   exchange.failure = e; WARN breadcrumb; completeExchange; rethrow
                          finally MdcScope.close()   (guarded separately)

 … the client's converters read the body through the tee; the client closes the response …

 CapturingClientHttpResponse.close()
   delegate.close()  (connection back to the pool first)
   completeExchange(exchange)       → exactly-once CAS; gauge exchanges.open -= 1
   ExchangeLogEmitter.logExchange   → exactly-once CAS, then the event
```

The emitter computes duration, reads the **final** status and headers off the real response, classifies
level/outcome/cause, records body sizes, gates on the logger level, opens the emission `MdcScope` (with
trace ownership, see [Common guide §7.6](../../docs/GUIDE.md#76-trace-correlation)), selects the response headers, decodes the captured
bodies and writes one event.

### 2.4 Emission point: response close

Emitting when the interceptor returns would report a response nobody has read yet: a body of zero bytes,
a duration that excludes the read, and — for the client's own error handling, which reads the body of a
4xx/5xx to build its exception — a picture of the exchange that is simply not over. `RestClient` and
`RestTemplate` both close the response in a `finally` when they are done with it, so the response's
`close()` is the moment the exchange truly ends. The interceptor therefore hands back a
`CapturingClientHttpResponse` whose `close()` completes the exchange:

- after the message converters read the body (through the tee),
- after the client's status handlers read the body for their exception,
- for a streaming result (`exchange(..., close = false)`, an `InputStreamResource` body), when the
  application closes it.

So the logged status, response headers and captures are final. Two consequences:

1. `adapter_duration_ms` measures **response occupancy** including the body read, not bare round-trip
   time ([§4.2](#42-duration-is-response-occupancy)).
2. Everything rests on the response being closed. The gauge `adapter.logging.exchanges.open` makes that
   assumption measurable ([§4.3](#43-a-response-that-is-never-closed)); the exactly-once CAS on
   `Exchange.completed` makes a double close harmless.

A call that produces **no response** — connection refused, DNS failure, a timeout before the status line
— emits right away from the interceptor's catch block: `-> -` in the message, no status field,
`adapter_outcome=failure` (or `timeout`, [§4.5](#45-timeouts-and-how-they-are-recognised)), the exception
attached as the cause. A short **WARN breadcrumb** with the exception's `toString` is logged first on the
module's own logger (`eu.inqudium.legatium.restclient.logging.ClientRequestLoggingInterceptor`) — not on
the exchange logger (one event per call is that stream's contract) — and the exception is rethrown
**unchanged** for the client to map (`ResourceAccessException` and friends).

### 2.5 The body tee

Bodies are never pre-read, buffered or replayed:

- The **request body** is what the interceptor is handed: `RestClient` and `RestTemplate` buffer the
  outgoing body into a byte array before the interceptor chain runs, so the capture simply copies (up to
  `max-body-bytes`) and counts it at wiring time — **before the wire call**. It is complete and final by
  construction, but it is what the client is *about to send*, not what reached the peer: the interceptor
  API has no seam at the actual write. The field `adapter_request_body` is documented as exactly that (and
  is the evidence a refused call leaves); the meter `adapter.request.body.size`, documented as bytes that
  flowed, records its sample only for an exchange that received a response — the one proof this seam has
  that the request went out. There is no read state on the request side.
- The **response body** is teed as the application reads it: `CapturingClientHttpResponse.getBody()`
  wraps the delegate's stream once; every `read` copies (up to the limit) and counts; the body counts as
  consumed to its end when the application sees the EOF **or** when the byte count reaches the length the
  response declared — a trustworthy `Content-Length`, i.e. none with a `Content-Encoding`, handed to the
  capture at handover. The second rule exists because Spring's `ByteArrayHttpMessageConverter` reads
  exactly `Content-Length` bytes with `readNBytes` and never asks for the EOF (the engines' streams
  return `0` for that final zero-length read, not `-1`); without it every `byte[]` answer counted as
  `partial`. The declared length is peer-controlled input and is treated as such: it only ever feeds the
  completeness comparison — never an allocation, a read or a wait — and a non-numeric value is folded to
  "unknown" rather than counted as a wiring failure. Nothing is withheld, so streaming behaviour and the
  connection pool's view of the body are those of an unwrapped response.
- `BoundedBodyCapture` is the target: a `ByteArrayOutputStream` of at most `max-body-bytes` and a total
  byte counter. With limit `0` it runs in **count-only** mode for the body-size meters. Visibility from
  the reading thread to the closing thread (usually the same; not necessarily) is established by the
  capture itself: the volatile `totalBytes` is written last in every mutation.

The captures exist only when a body is logged (in any mode — `on-failure` needs the bytes before the outcome is known, [Common guide §6.3](../../docs/GUIDE.md#63-body-logging-and-body-measuring)) **or** measured; without either, the response wrapper still
exists (the close hook is the emission point), but the body stream passes through with the read-failure
guard only.

**The capture mirrors consumption, not transmission.** The log shows exactly the bytes the application
actually read — no more. A response body the application never opens (`toBodilessEntity()`, a
`ResponseEntity<Void>`) is logged as absent and records no size sample, even though the peer sent one; a
body read only partially is captured to exactly that extent, and the `[truncated, N bytes total]` note
counts what flowed, not `Content-Length`. This is the deliberate trade-off against a replaying buffer —
the log tells the truth about what the application processed, and streaming stays untouched. Because of
that, the log cannot tell a body the peer sent but the application dropped from one that was never sent;
the counter `adapter.response.body.read` ([Common guide §7.4](../../docs/GUIDE.md#74-meters)) exists for exactly that distinction: such a
never-opened body counts `unread`, a body read to its EOF or to its declared `Content-Length` counts
`complete`, and an answer that carries no body by the protocol (a 1xx, 204 or 304, a `Content-Length: 0`) —
which `RestClient` and `RestTemplate` never open — counts `complete` at handover, so a route of deletes
and updates does not read as discarded payload.

### 2.6 MDC coverage

The module advertises "call identity in MDC while the wire call runs". Concretely:

| Thread / phase | Mechanism | Covered |
|---|---|---|
| The wire call (inner interceptors, the request factory, the HTTP engine's own logging) | call-wide `MdcScope` in `intercept` | yes |
| The body read and the close, after the interceptor returned | — the client's converters run in the caller's context | no (the caller's ambient MDC applies — usually the same thread, with its inbound identity) |
| The emission at close | `MdcScope` in the emitter, with trace ownership | yes |
| The emission at a close on **another** thread | the caller's MDC snapshot (`CallerMdcSnapshot`), taken at wiring, restored around the emission — outside the `MdcScope` | yes, for the keys the caller had; see below |

`MdcScope` is an **additive overlay**: it puts the three `adapter_*` keys and restores the previous values
on close (threads are pooled; an inbound request's filter may own other keys). Around the call it leaves
the trace keys alone — a tracing bridge's own scope is authoritative there. Around the emission it
**owns** them: a parsed id is installed, an unparsed one is removed for the scope's lifetime, so a stale
bridge id on the closing thread can never join the event to a foreign trace.

The one thing the overlay never does is *replace*: `endpoint_request_id` (Limesium) and every other
ambient key stay visible on the client line, which is how inbound and outbound lines join without either
library knowing about the other.

**A response closed on another thread.** A host that takes the response as a stream and hands it to
another thread — a download piped into a pooled writer — closes it there, and that thread carries none
of the caller's MDC. For exactly this case the interceptor snapshots the caller's MDC at wiring, on the
calling thread, minus the module's own keys and the trace keys (those belong to the emission scope; a
nested client call must not carry the outer call's identity), and the emitter restores the snapshot
around the exchange line **only when the closing thread is not the calling one**
([ADR-0011](../../docs/adr/ADR-0011-blocking-twin-snapshots-the-callers-mdc.md)). On the caller's thread
the live MDC is the truth and the snapshot is not applied, so a value updated between the call and the
close stays the newer one. On another thread the snapshot wins for the keys it holds, a key only that
thread has stays visible, and every touched key is restored on close. The cost is one map copy per call,
nothing for an empty MDC; there is no configuration key for it. A throwing MDC adapter at capture or at
restore costs the snapshot, counted as `stage=wiring`, never the event. The layering — the caller's
context outside, the module's own `MdcScope` inside — is the one the WebClient twin uses with the Reactor
Context as its source ([WebClient guide §2.6](../../legatium-webclient-logging/docs/GUIDE.md#26-mdc-and-the-reactive-call)).

### 2.7 Fail-open contract

A logging component must never fail the call it describes. The module enforces that at every boundary
where it calls host-provided code (MDC adapter, appenders, `MeterRegistry`, the client's request and
response objects):

| Stage | Where | What happens on failure | Counted as |
|---|---|---|---|
| wiring | `wireExchange` (correlation bean, header selection, capture construction) | the interceptor degrades to a plain pass-through for this call | `failopen{stage=wiring}` |
| wiring | `MdcScope` open | the call runs without call MDC | `failopen{stage=wiring}` |
| wiring | `MdcScope` close | restoration lost; never masks an exception propagating out of the call | `failopen{stage=wiring}` |
| wiring | `CallerMdcSnapshot` capture or restore (ADR-0011) | the event follows with the module's own identity, without the caller's keys | `failopen{stage=wiring}` |
| wiring | body-size recording, operational counter updates | the event follows without the sample / the count | `failopen{stage=wiring}` |
| arrival | `logRequestStart` (including the level gate) | the arrival line is dropped | `failopen{stage=arrival}` |
| emission | `logExchange` — everything after the exactly-once CAS, including the status read | the exchange event is **lost**; the close returns normally | `failopen{stage=emission}` |
| registration | `ClientLoggingMetrics.registerOrFallback` | the conflicting meter lives in a private registry, warned once per name | — |

What the promise behind the stages is, where the reports go (the module's own loggers, never the
exchange logger), why the exchange log is an observability feature and not an audit trail, and why the
boundary is `Exception` and not `Throwable`, is one contract for both twins —
[Common guide §8.2](../../docs/GUIDE.md#82-fail-open-contract). Specific to this stack: an `Error`
thrown by the logging backend *during* the emission at response close is outside the promise and reaches
the client's `finally`.

---

## 3. Using it in a foreign project

Everything that is one contract for both twins — prerequisites, the dependency, overriding beans, the
logging backend and structured output, the index mapping, the configuration and the metrics — is written
once, in the [Common guide](../../docs/GUIDE.md). This chapter holds what is specific to the interceptor:
how it is wired into a Boot application, how to wire it by hand, where it sits in the chain, and how to verify
the integration.

### 3.1 Automatic wiring

The shipped activation is not the interceptor bean but the two customizers that attach it. The hooks are
Boot's **builder Spring beans**, both defined in the `spring-boot-restclient` module:

| Boot bean | Defined by | Scope | When the customizers run |
|---|---|---|---|
| `RestClient.Builder` | `RestClientAutoConfiguration` | **prototype** — every injection point receives a fresh builder, so one adapter's `baseUrl` or default headers never leak into another's | on creation, before the builder is handed out: every `RestClientCustomizer` bean, in bean order |
| `RestTemplateBuilder` | `RestTemplateAutoConfiguration` | singleton, immutable — each configuring call returns a new builder | at `build()`: every `RestTemplateCustomizer` bean, in bean order, on the freshly built `RestTemplate` |

This module contributes one customizer of each kind, both ordered at `Ordered.LOWEST_PRECEDENCE - 10`:
the `RestClientCustomizer` does exactly `builder.requestInterceptor(interceptor)`, the
`RestTemplateCustomizer` appends the interceptor to the template's interceptor list — in both cases the
interceptor lands at the **end** of the list, innermost
([§3.3](#33-interceptor-order-and-other-interceptors)).

Consequently the rule for the host is: **every adapter obtains its client from the injected builder
bean.** Constructor injection is the usual form; a `@Bean` method parameter or a builder obtained from
the `ApplicationContext` is the same bean with the same customizers applied.

```kotlin
@Service
class ThingsAdapter(builder: RestClient.Builder) {       // Boot's RestClient.Builder bean, injected
    private val client = builder
        .baseUrl("https://api.example.com")
        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .build()

    fun thing(id: Long): Thing =
        client.get().uri("/things/{id}", id).retrieve().body(Thing::class.java)!!
}

@Service
class LegacyThingsAdapter(builder: RestTemplateBuilder) { // Boot's RestTemplateBuilder bean, injected
    private val template = builder.rootUri("https://api.example.com").build()

    fun thing(id: Long): Thing = template.getForObject("/things/{id}", Thing::class.java, id)!!
}
```

Covered by the automatic wiring:

- every `RestClient` built from an injected `RestClient.Builder`, however many `build()` calls the
  adapter makes on it;
- every `RestTemplate` built through the injected `RestTemplateBuilder`, whatever chain of configuring
  calls precedes the `build()`;
- every HTTP service client group Boot builds through its `RestClient.Builder`
  (`HttpServiceClientAutoConfiguration`, `@ImportHttpServices`) — the proxies' underlying client carries
  the interceptor like any other.

**Not** covered — these clients never meet Boot's customizers and therefore log nothing:

- `RestClient.create()` / `RestClient.create(baseUrl)` and the static `RestClient.builder()`;
- a `RestTemplate` constructed directly (`RestTemplate()`, `RestTemplate(requestFactory)`);
- a builder the host constructs and then customises itself.

For those, [§3.2](#32-manual-wiring) applies.

The automatic wiring is conditional on two things, both pinned by `ClientLoggingAutoConfigurationTest`:
`adapter-logging.enabled` (default `true`; `false` removes the interceptor bean and both customizers
together), and Boot's customizer classes being present (`@ConditionalOnClass` on each nested
configuration) — without `spring-boot-restclient` both back off silently while the interceptor bean
remains. Note that since Boot 4 the web starters no longer pull `spring-boot-restclient`: a host with
only `spring-boot-starter-web` has `RestClient` and `RestTemplate` on the classpath (from `spring-web`)
but neither Boot's builder beans nor the customizer contracts — it adds `spring-boot-starter-restclient`,
or wires by hand. The wiring itself is fail-open like everything else: a failure inside the interceptor's
setup for a call degrades that call to a pass-through with a `stage=wiring` report
([§2.7](#27-fail-open-contract)); the customizers cannot fail in a way that breaks a builder.

To confirm the attachment at runtime — in a test or a startup check — read the builder's interceptor
list; the module's interceptor must be the last entry (a built `RestTemplate` exposes the same through
`interceptors`):

```kotlin
val builder: RestClient.Builder = context.getBean(RestClient.Builder::class.java)
builder.requestInterceptors { interceptors -> check(interceptors.last() is ClientRequestLoggingInterceptor) }
```

### 3.2 Manual wiring

The interceptor bean `ClientRequestLoggingInterceptor` exists in every enabled context; only its
**attachment** depends on Boot's builders. Attach it yourself when a client does not pass through them:

| Situation | Why the automatic wiring does not reach it |
|---|---|
| The host builds clients by hand — `RestClient.create(...)`, the static `RestClient.builder()`, a `RestTemplate` constructed directly, or a builder it constructs itself | Boot's customizers run only on the builder beans Boot defines; a client built elsewhere never sees them |
| `spring-boot-restclient` is absent — the host depends on `spring-web` directly, or only on `spring-boot-starter-web`, without `spring-boot-starter-restclient` | both nested customizer configurations are `@ConditionalOnClass` and back off; there are no builder beans either, so every client is hand-built anyway |
| A builder obtained from Boot is customised **after** the customizers ran and the logging interceptor must stay innermost | interceptors the host appends on that builder land behind this one and run *inside* it ([§3.3](#33-interceptor-order-and-other-interceptors)); where the logged request must be what those later interceptors produce, the host takes over the ordering |
| A client is built outside a Spring context — a library's own client, an integration test without Boot | there is no context to hold the bean, so the interceptor is constructed directly (below) |

The mechanics are one line per client: inject the bean and append it as the **last** interceptor, so it
sits closest to the wire and sees the request as the peer receives it, once per attempt of any retry
outside it:

```kotlin
@Configuration(proxyBeanMethods = false)
class ThingsClientConfiguration {
    @Bean
    fun thingsClient(loggingInterceptor: ClientRequestLoggingInterceptor, auth: AuthenticationInterceptor): RestClient =
        RestClient.builder()
            .baseUrl("https://api.example.com")
            .requestInterceptor(auth)                 // outside: its header is what gets logged
            .requestInterceptor(loggingInterceptor)   // last = innermost, closest to the wire
            .build()

    @Bean
    fun legacyTemplate(loggingInterceptor: ClientRequestLoggingInterceptor): RestTemplate =
        RestTemplate().apply { interceptors = interceptors + loggingInterceptor }
}
```

Rules for manual wiring:

- **Reuse the one bean; do not construct a second interceptor in a Boot context.** The meters are
  identified by name, so every interceptor on one `MeterRegistry` shares one metrics owner and the
  `adapter.logging.exchanges.open` gauge reports the total across them
  ([Common guide §7.4](../../docs/GUIDE.md#74-meters)). A second instance would not break anything,
  but it buys nothing.
- **Honour the switch.** With `adapter-logging.enabled=false` the bean does not exist, and a plain
  injection point fails to start the context. A client configuration that must survive the switch takes
  an `ObjectProvider<ClientRequestLoggingInterceptor>` and attaches the interceptor only if it is
  available:

  ```kotlin
  @Bean
  fun thingsClient(loggingInterceptor: ObjectProvider<ClientRequestLoggingInterceptor>): RestClient =
      RestClient.builder()
          .baseUrl("https://api.example.com")
          .also { builder -> loggingInterceptor.ifAvailable { builder.requestInterceptor(it) } }
          .build()
  ```

- **Activation is not the host's business.** Host and path activation (`adapter-logging.exclude-hosts`,
  `include-path-patterns`, `exclude-path-prefixes`) is evaluated inside the interceptor
  ([Common guide §6.4](../../docs/GUIDE.md#64-activation-hosts-and-paths)), so a manually attached interceptor applies the same rules as
  an automatically attached one. There is no need to attach it selectively.
- **Ordering is the host's business.** The automatic wiring guarantees "innermost" by its late
  customizers; a manual `requestInterceptor(...)` call is appended wherever it is made. Put it last.

Outside a Spring context the interceptor is constructed directly. The constructor takes the bound
properties, the time source, the id generator and a `MeterRegistry`, plus an optional trailing
`HeaderValueMasker` — when omitted, the masker the properties' `masking-key` selects, exactly as the
auto-configuration's default bean, so a configured key is honoured however the interceptor is built — all
defaults are public:

```kotlin
val interceptor = ClientRequestLoggingInterceptor(
    ClientLoggingProperties(),              // every default; or a copy(...) with the fields to change
    NanoTimeSource.SYSTEM,
    CorrelationIdGenerator.DEFAULT,
    SimpleMeterRegistry(),                  // or the registry the surrounding code owns
)
val client = RestClient.builder().baseUrl(url).requestInterceptor(interceptor).build()
```

Everything else is unchanged by the way the interceptor was attached: emission point, outcomes, meters,
the call-wide MDC, header sections, body capture and the fail-open contract behave exactly as under the
automatic wiring — the interceptor does not know how it got onto the chain.

### 3.3 Interceptor order and other interceptors

The customizers are ordered at `Ordered.LOWEST_PRECEDENCE - 10`, so the interceptor is appended **behind**
the interceptors of customizers ordered before that value and of the builder's own configuration, and
runs **inside** them — closest to the wire:

- an authentication interceptor outside it has already added its header, so the logged (and masked)
  request headers are what the peer receives;
- a retrying interceptor outside it invokes it once per attempt — one line per attempt, each an honest
  crossing ([§4.7](#47-retries-yield-one-line-per-attempt));
- interceptors a host adds **after** the customizers ran (directly on a builder it obtained from Boot)
  run inside this one and are outside that guarantee — they see the request after this interceptor did.

**"Earlier" means ordered earlier.** A `RestClientCustomizer` or `RestTemplateCustomizer` bean
**without** an `@Order` sits at `Ordered.LOWEST_PRECEDENCE` — *after* the module's `LOWEST_PRECEDENCE - 10`
— and is applied later: its interceptor is appended behind the logging interceptor and runs inside it.
An authentication header added there is not on the logged line, and a retry performed there is one line
spanning all attempts. To have the module observe a host interceptor, order its customizer before the
module's, `@Order(0)` being the usual choice; the auto-configuration test pins both positions. The room
below the module's order is deliberate: a customizer that must see the fully configured interceptor
list (a diagnostics wrapper) has it.

The `traceparent` header is not affected by the order at all: the client observation Boot registers
injects it into the request **before** any interceptor runs ([Common guide §7.6](../../docs/GUIDE.md#76-trace-correlation)).

Activation is evaluated **in the interceptor** (`shouldNotFilter`), so its semantics are byte-identical
with the WebClient twin. If the host needs a different position, it attaches the bean itself
([§3.2](#32-manual-wiring)).

### 3.4 Verifying the integration

1. Make any call through a Boot-built `RestClient`:

   ```kotlin
   restClientBuilder.baseUrl("https://httpbin.org").build().get().uri("/get").retrieve().body(String::class.java)
   ```

   Expect one `adapter-http-exchange` line with `adapter_request_id=…`. Without tracing configured, the
   peer received an `X-Correlation-Id` with that id (httpbin echoes request headers in its body). With
   Micrometer Tracing configured, expect `traceId=… spanId=…` on the line and **no** `X-Correlation-Id`
   at the peer (ADR-0002).

2. Log something between building and executing the request from inside an inner interceptor, or
   enable the engine's debug logging, and confirm `adapter_request_id` is on those lines too.

3. Point the client at a closed port and confirm: an immediate WARN breadcrumb on
   `eu.inqudium.legatium.restclient.logging.ClientRequestLoggingInterceptor`, then the exchange line with
   `-> -`, `adapter_outcome=failure` at ERROR with the cause attached.

4. Check the meters (with actuator):

   ```bash
   curl -s localhost:8080/actuator/metrics/adapter.logging.events
   curl -s localhost:8080/actuator/metrics/adapter.logging.exchanges.open
   ```

   `events` should equal the number of logged lines; `exchanges.open` should be `0` when idle.

### 3.5 Naming a client

`adapter_url_host` is the host of the request URI. As long as every dependency has its own host that is
the coordinate dashboards split by; once the application reaches its dependencies through an **egress
sidecar** or a forward proxy, the URI names the sidecar for every call and every dependency lands in
one bucket. The interceptor cannot tell the clients apart from the request alone — the application
can, and it says so once per client through a request attribute the interceptor reads at wiring time
(`ClientRequestLoggingInterceptor.ADAPTER_NAME_ATTRIBUTE`,
[ADR-0009](../../docs/adr/ADR-0009-adapter-name-is-a-request-attribute.md)):

```kotlin
@Configuration(proxyBeanMethods = false)
class ClientsConfiguration {
    @Bean
    fun billingClient(builder: RestClient.Builder): RestClient =
        builder
            .baseUrl("http://localhost:15001/billing")
            .defaultRequest { it.attribute(ClientRequestLoggingInterceptor.ADAPTER_NAME_ATTRIBUTE, "billing") }
            .build()

    @Bean
    fun geoClient(builder: RestClient.Builder): RestClient =
        builder
            .baseUrl("http://localhost:15001/geo")
            .defaultRequest { it.attribute(ClientRequestLoggingInterceptor.ADAPTER_NAME_ATTRIBUTE, "geo-lookup") }
            .build()
}
```

`defaultRequest` runs for every request the client builds, so the attribute is on each of them before
the interceptor chain starts; a per-call `attribute(...)` on the request spec overrides it. Every call
of a named client then carries `adapter_name` on the completion event and on the arrival line, and the
three body meters carry the name as their `name` tag (`UNNAMED` for a client nobody named). A blank
value counts as no name; the value is otherwise the host's vocabulary and is neither folded nor
validated.

**`RestTemplate`** has no `defaultRequest`. It does hand its interceptors an `HttpRequest` with
attributes, so an interceptor of the host's own — registered **before** the logging interceptor, which
must stay last ([§3.2](#32-manual-wiring)) — sets it:

```kotlin
@Bean
fun legacyTemplate(loggingInterceptor: ClientRequestLoggingInterceptor): RestTemplate =
    RestTemplate().apply {
        interceptors =
            listOf(
                ClientHttpRequestInterceptor { request, body, execution ->
                    request.attributes[ClientRequestLoggingInterceptor.ADAPTER_NAME_ATTRIBUTE] = "legacy-billing"
                    execution.execute(request, body)
                },
                loggingInterceptor,
            )
    }
```

**Verifying it:** make one call through a named client and expect `adapter_name=billing` beside
`adapter_url_host=localhost:15001` on the exchange line; a call through an unnamed client carries no
`adapter_name` at all. With `measure-response-body-size` on,
`curl -s localhost:8080/actuator/metrics/adapter.response.body.read` lists `name` among the available
tags.

The attribute string is the same on the WebClient twin, so a host carrying both jars names its clients
with one literal. The field itself and the meter tag are documented once, in the
[Common guide §7.7](../../docs/GUIDE.md#77-naming-a-client).

---

## 4. Special characteristics

### 4.1 Differences to the WebClient twin

Everything not listed here behaves exactly as in `legatium-webclient-logging`.

| Concern | This module | WebClient twin |
|---|---|---|
| Disposition vocabulary | `success` / `failure` / `timeout` | plus **`cancelled`** — a cancelled subscription (a downstream `timeout()` operator, a `take`, a disposed caller) is the reactive reality a blocking call cannot have |
| Emission point | response **close** | the response **body's terminal signal** |
| Never-completing exchange | a response the application never closes | a response body nobody subscribes to or releases |
| Request body | the byte array the client hands the interceptor — complete, captured at wiring **before** the wire call (the field shows what was about to be sent; the size meter records only once a response proves it went out) | teed at the connector's `writeWith` as the inserter writes it |
| Call-wide MDC | thread-local, for the wire call | none — the call hops event-loop threads; emission MDC and the message inline only |
| The caller's context on the exchange line | on the thread; for a close on **another** thread, the caller's MDC snapshot taken at wiring ([§2.6](#26-mdc-coverage), ADR-0011) | restored from the **Reactor Context** through the host's `ThreadLocalAccessor`s (ADR-0010) — same layering, different source |
| Read failure mid-body | `IOException` from the tee stream, reported and rethrown | the body `Flux`'s error signal |
| URI template | recorded by `RestClient`; **never** by `RestTemplate` | recorded by `WebClient` |
| Attachment | `RestClientCustomizer` + `RestTemplateCustomizer` | `WebClientCustomizer` |

### 4.2 Duration is response occupancy

`adapter_duration_ms` runs from wiring (before the wire call) to response close (after the body was read
and the client was done). A peer that answers the status line fast but streams the body slowly is slow
by this measure — which is the truth an operator wants, and the same rule Limesium applies inbound
(request occupancy). Bare round-trip latency is what `http.client.requests` already measures.

### 4.3 A response that is never closed

The emission rests on the response being closed. `RestClient`'s `retrieve()`, `body(...)`, `toEntity(...)`
and `exchange(..., close = true)` (the default) close in a `finally`; `RestTemplate` closes in
`doExecute`'s `finally`. The one path that does not is a raw `exchange(fn, close = false)` — or a
streaming result (`InputStreamResource`, a `ResponseEntity<InputStream>`) where `RestClient` deliberately
hands the open stream to the caller. There the caller owns the close, and until it happens the exchange
stays **open on the gauge** `adapter.logging.exchanges.open`. A monotonically growing baseline is the
signal that responses are leaking — a resource leak in the host, visible through the module's liveness
meter before it becomes a pool exhaustion.

### 4.4 Failures on the response the client was handed

The status line arrived, then the connection died mid-body (a reset, a read timeout while streaming). The
tee reports the `IOException` to the exchange and rethrows it unchanged; at close the event is
`adapter_outcome=failure` (or `timeout`) **with the status that was received** — "200 but failed" is exactly
what happened, and hiding either half would mislead. The captured prefix of the body is logged as far as
it flowed.

The same holds for **every** other operation on the response that can fail the caller: opening the body,
asking for status, status text or headers (the snapshot at handover tolerates a refusing engine and logs
`-> -`, but the client's own later access propagates), `available()`, closing the body stream, and the
response's own `close()` — a pooled connection that cannot be returned throws there, immediately before
the emission in the `finally`. Each is recorded on the exchange before the exception propagates unchanged,
so the caller and the event never disagree: a response that failed the caller is never logged as
`success`, and a throwing close yields exactly one event, a `failure` carrying that exception.

### 4.5 Timeouts and how they are recognised

A timeout is the one client-side disposition an operator reads differently from every other failure (the
peer is slow, not broken), so it has its own outcome value at WARN. The shared `Timeouts` classification
walks the exception's **cause chain** and, per link, the class hierarchy by name: the JDK's
`SocketTimeoutException`, `java.net.http.HttpTimeoutException` (and its connect subtype) and
`java.util.concurrent.TimeoutException` are matched as types; Netty's `io.netty.handler.timeout.
TimeoutException` family and its connect timeout `io.netty.channel.ConnectTimeoutException` (a
`ConnectException`, which no JDK timeout type covers) by their fully qualified names, so the WebClient
twin recognises Reactor Netty's read and connect timeouts without a Netty dependency here. Engines wrap their timeouts (`IOException` over
`SocketTimeoutException`; `RestClient` wraps once more into `ResourceAccessException` *after* the
interceptor saw the original) — hence the chain walk. Anything else is a plain `failure`.

### 4.6 RestTemplate has no URI template

`RestClient` records the URI template of a call made through `uri(String, Object...)` as a request
attribute (`org.springframework.web.client.RestClient.uriTemplate`, mirrored by the module and pinned
against the client by `UriTemplateAttributeTest`) — that is `adapter_url_template`, the low-cardinality
aggregation half of the path pair. `RestTemplate` sets its template only on the observation context, not
on the request, so `RestTemplate` calls log the path alone and their body meters fall under
`uri=UNKNOWN`. A host that wants the template on `RestTemplate` calls migrates to `RestClient`; the
module does not reconstruct templates by guessing. The client's name (`adapter_name`, ADR-0009) is a
request attribute too, and `RestTemplate` has no `defaultRequest` to set it on: an interceptor of the
host's own, registered before this one, sets it instead ([§3.5](#35-naming-a-client)).

### 4.7 Retries yield one line per attempt

The interceptor sits innermost ([§3.3](#33-interceptor-order-and-other-interceptors)), so a retrying
interceptor (or a resilience decorator around the client) invokes it once per attempt. Each attempt is a
crossing and gets its own line — with the same `adapter_request_id` under a trace, or a **new** generated
id per attempt on a traceless call (each attempt wires afresh and the retried request already carries the
first attempt's correlation header only if the retrying layer reuses the mutated request object; a rebuilt
request gets a new id). Dashboards counting calls per peer count attempts; `http.client.requests` does
the same.

Tracing making every call traced, the one-metrics-owner-per-registry rule and the masking fingerprint are
one behaviour for both twins — [Common guide §7.6](../../docs/GUIDE.md#76-trace-correlation),
[§7.4](../../docs/GUIDE.md#74-meters) and [§6.2](../../docs/GUIDE.md#62-header-sections); the shared
code they rest on is [Common guide §9](../../docs/GUIDE.md#9-shared-code-legatium-common-inlined-by-shade).

---

## 5. Appendix

### 5.1 File map

```
legatium-restclient-logging/
├── pom.xml                                   library deps only
├── README.md                                 module summary, field family, property table
├── docs/
│   ├── GUIDE.md                              this document
│   └── api-module.md                         the module page of the Dokka API reference
└── src/
    ├── main/kotlin/eu/inqudium/legatium/restclient/logging/
    │   ├── ClientLoggingAutoConfiguration.kt      beans, the two late customizers
    │   ├── ClientRequestLoggingInterceptor.kt     the interceptor: activation, wiring, call scope, no-response path
    │   ├── CapturingClientHttpResponse.kt         response wrapper: body tee, failure report for every delegate call, close = emission
    │   ├── Exchange.kt                            per-exchange state and the exactly-once guards
    │   ├── ExchangeLogEmitter.kt                  arrival line and completion event
    │   └── BoundedBodyCapture.kt                  bounded capture target, read state
    │   (ClientLoggingProperties, ClientLogFields, Traceparent, Timeouts, Mdc, NanoTimeSource,
    │    CorrelationIdGenerator, HeaderLogProperties, BodyCapture helpers and the fail-open guards
    │    live in ../legatium-common - inlined, Common guide §9)
    ├── main/resources/META-INF/spring/…AutoConfiguration.imports
    ├── test/java/…/BoundedBodyCaptureFuzzTest.java    Jazzer target (regression mode in every build)
    └── test/kotlin/eu/inqudium/legatium/restclient/logging/  see the suite overview below
```

Test-suite overview (the generated [test-evidence page](https://inqudium.github.io/legatium/tests/test-evidence/)
lists every test with its rationale):

| Suite | Scope |
|---|---|
| Unit suites (`ClientRequestLoggingInterceptorTest`, `…BodyAndHeaderTest`, `…MetricsTest`, `BoundedBodyCaptureTest`) | mock request/response driven, deterministic: line format, identity, levels/outcomes, emission at close, activation, tees, meters, fail-open stages |
| `ClientLoggingAutoConfigurationTest` | the shipped activation: beans, customizers attaching the interceptor to Boot's builders, back-off, the optional-dependency boundary |
| `ClientRequestLoggingInterceptorIntegrationTest` | end to end through Boot's `RestClient.Builder` / `RestTemplateBuilder` and the JDK engine against a real HTTP peer: templates, bodies, the wire correlation header, refused connection, read timeout |
| `ClientRequestLoggingTracingIntegrationTest` | ADR-0002 beside a real Brave bridge: the injected `traceparent`, the log-to-trace join, no correlation header on traced calls, every call traced |
| Engine suites (`RequestFactoryContract` run as `JdkClientRequestFactoryIntegrationTest`, `HttpComponentsRequestFactoryIntegrationTest`, `JettyRequestFactoryIntegrationTest`, `ReactorNettyRequestFactoryIntegrationTest`, `SimpleRequestFactoryIntegrationTest`) | the engine-agnosticism contract against every request factory Spring ships: the body tee and its complete read state on the engine's own stream, the wire correlation header, the engine's real read and connect timeout types as `timeout` through `RestClient`'s wrapper (the connect timeout provoked by a loopback tarpit, `Tarpit`), a refused connection as the `failure` control, and per engine whether a gzip answer reaches application and log decoded (JDK client via Spring's compression support, Apache HC5, Jetty) or as sent (Reactor Netty, `HttpURLConnection`) |
| Lockstep/contract tests (`TwinContractTest`, `UriTemplateAttributeTest`) | pin the message text, this stack's outcome vocabulary and the mirrored `RestClient` attribute; the shared literals (`SharedContractTest`), the field/template and configuration/reference lockstep (`ClientLogFieldTest`, `ClientLoggingReferenceConfigTest`, `ClientLoggingPropertiesTest`) and the metrics owner's registration behaviour (`ClientLoggingMetricsTest`) live once in legatium-common; the shaded jar itself is exercised by the standalone `consumer-smoke/` build |

Fuzzing of the shared `Traceparent` parser and header masking lives in legatium-common; the bounded
capture's fuzz target lives here.

### 5.2 Related documents

- [Common guide](../../docs/GUIDE.md) — everything that is one contract for both twins: prerequisites,
  dependency, beans, logging backend, index mapping, configuration, fields, MDC keys, meters, trace
  correlation, scope and fail-open guarantees, the shared code.
- [`README.md`](../README.md) — module summary, field family, property table, meters.
- [`legatium-webclient-logging/README.md`](../../legatium-webclient-logging/README.md) — the twin's
  documentation; everything not listed in [§4.1](#41-differences-to-the-webclient-twin) applies there
  unchanged.
- [`/docs/adapter-logging-reference.yml`](../../docs/adapter-logging-reference.yml) — the complete commented
  configuration reference, bound by both twins.
- [`/docs/elk/README.md`](../../docs/elk/README.md) — the Elasticsearch component template for the
  `adapter_*` fields.
- [`/docs/adr/`](../../docs/adr/) — the decision records: fuzzing signal, trace identity, shared core,
  id generator, headers masked by default, bodies logged by outcome, the `adapter` vocabulary.
- [Limesium](https://github.com/Inqudium/limesium) — the inbound sibling: same design, `endpoint_*`
  fields, `endpoint-logging.*` namespace.
