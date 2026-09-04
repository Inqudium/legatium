# legatium-restclient-logging — Guide

One structured `adapter_*` log line per outbound HTTP exchange made through Spring's `RestClient` or
`RestTemplate`, with the exchange identity in the MDC while the wire call runs. This module is the
**reference implementation** of the adapter-logging family; its WebClient twin
[`legatium-webclient-logging`](../../legatium-webclient-logging/README.md) shares the message format, the
field family, the `adapter-logging.*` configuration and the meters. The inbound counterpart of the whole
family is the sibling project [Limesium](https://github.com/Inqudium/limesium).

This guide is the long-form companion to the module [README](../README.md). It explains what the module
does, how it is built, how to drop it into a foreign application, what can be configured, what it
measures, and which behaviours are specific to the blocking client stack. Everything here is derived from
the code under `src/main/kotlin/eu/inqudium/legatium/restclient/logging/`; when the two disagree, the
code wins.

## Table of contents

1. [Introduction](#1-introduction)
   1. [What the module does](#11-what-the-module-does)
   2. [What the module deliberately does not do](#12-what-the-module-deliberately-does-not-do)
   3. [The exchange line](#13-the-exchange-line)
   4. [The WebClient twin](#14-the-webclient-twin)
2. [Architecture](#2-architecture)
   1. [Component overview](#21-component-overview)
   2. [Auto-configuration and registration](#22-auto-configuration-and-registration)
   3. [Lifecycle of one exchange](#23-lifecycle-of-one-exchange)
   4. [Emission point: response close](#24-emission-point-response-close)
   5. [The body tee](#25-the-body-tee)
   6. [MDC coverage](#26-mdc-coverage)
   7. [Fail-open contract](#27-fail-open-contract)
   8. [Injectable collaborators](#28-injectable-collaborators)
3. [Using it in a foreign project](#3-using-it-in-a-foreign-project)
   1. [Prerequisites](#31-prerequisites)
   2. [Adding the dependency](#32-adding-the-dependency)
   3. [Interceptor order and other interceptors](#33-interceptor-order-and-other-interceptors)
   4. [Overriding beans](#34-overriding-beans)
   5. [Logging backend and structured output](#35-logging-backend-and-structured-output)
   6. [Index mapping (ELK)](#36-index-mapping-elk)
   7. [Verifying the integration](#37-verifying-the-integration)
4. [Configuration](#4-configuration)
   1. [Property reference](#41-property-reference)
   2. [Header sections](#42-header-sections)
   3. [Body logging and body measuring](#43-body-logging-and-body-measuring)
   4. [Activation: hosts and paths](#44-activation-hosts-and-paths)
   5. [Logger levels](#45-logger-levels)
   6. [Validation at startup](#46-validation-at-startup)
   7. [Example configurations](#47-example-configurations)
5. [Metrics and observation](#5-metrics-and-observation)
   1. [Log fields](#51-log-fields)
   2. [MDC keys](#52-mdc-keys)
   3. [Levels and outcomes](#53-levels-and-outcomes)
   4. [Meters](#54-meters)
   5. [Reading the meters together](#55-reading-the-meters-together)
   6. [Trace correlation](#56-trace-correlation)
6. [Special characteristics](#6-special-characteristics)
   1. [Differences to the WebClient twin](#61-differences-to-the-webclient-twin)
   2. [Duration is response occupancy](#62-duration-is-response-occupancy)
   3. [A response that is never closed](#63-a-response-that-is-never-closed)
   4. [Failures while reading the body](#64-failures-while-reading-the-body)
   5. [Timeouts and how they are recognised](#65-timeouts-and-how-they-are-recognised)
   6. [RestTemplate has no URI template](#66-resttemplate-has-no-uri-template)
   7. [Retries yield one line per attempt](#67-retries-yield-one-line-per-attempt)
   8. [Tracing makes every call traced](#68-tracing-makes-every-call-traced)
   9. [One metrics instance per registry](#69-one-metrics-instance-per-registry)
   10. [Masking is a fingerprint, not a secret](#610-masking-is-a-fingerprint-not-a-secret)
   11. [Shared code: legatium-common, inlined by Shade](#611-shared-code-legatium-common-inlined-by-shade)
7. [Appendix](#7-appendix)
   1. [File map](#71-file-map)
   2. [Related documents](#72-related-documents)

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
emission, metrics — can ever fail, delay or alter the call it describes.

### 1.2 What the module deliberately does not do

- **No request rates, latencies or status distributions as metrics.** Boot's `http.client.requests` and
  the structured log fields cover those; the module's meters observe only what those cannot show
  ([§5.4](#54-meters)).
- **No retries, no circuit breaking, no request rewriting.** The module observes; the one thing it adds to
  a request is the correlation header on a traceless call without one ([§5.6](#56-trace-correlation)).
- **No body masking transformers and no per-key response sampling.** Bodies are logged verbatim up to the
  capture limit, and the logger level is the only volume control ([§4.5](#45-logger-levels)).
- **No replaying body cache.** The response tee is passive; an unread response body is logged as absent.
- **No exporting of a `MeterRegistry`.** The host's registry is consumed if present; otherwise a private
  `SimpleMeterRegistry` absorbs the values.
- **No clients built by hand.** The customizers cover every client built through Boot's builders (and the
  HTTP service client groups built from them); a hand-built `RestClient` gets the interceptor bean added
  by the host ([§3.4](#34-overriding-beans)).

### 1.3 The exchange line

On the logger `http-adapter-exchange` (configurable) a completed exchange looks like this in a plain-text
appender:

```
Client http exchange POST https://api.example.com/things/42 -> 200 [adapter_request_id=4bf92f3577b34da6a3ce929d0e0e4736 traceId=4bf92f3577b34da6a3ce929d0e0e4736 spanId=00f067aa0ba902b7]
```

The trace suffix appears only when the outgoing request carried a conformant W3C `traceparent` header —
its trace id then doubles as the request id (ADR-0002). Alongside the message, the event carries SLF4J
key-values that a structured encoder turns into fields:

```json
{
  "message": "Client http exchange POST https://api.example.com/things/42 -> 200 [adapter_request_id=4bf92f3577b34da6a3ce929d0e0e4736 traceId=4bf92f3577b34da6a3ce929d0e0e4736 spanId=00f067aa0ba902b7]",
  "level": "INFO",
  "logger": "http-adapter-exchange",
  "adapter_outcome": "success",
  "adapter_duration_ms": 17,
  "adapter_request_method": "POST",
  "adapter_response_status_code": 200,
  "adapter_url_host": "api.example.com",
  "adapter_url_path": "/things/42",
  "adapter_url_template": "https://api.example.com/things/{id}",
  "adapter_request_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "adapter_method": "POST",
  "adapter_route": "https://api.example.com/things/42",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "00f067aa0ba902b7",
  "endpoint_request_id": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

The `adapter_request_id` / `adapter_method` / `adapter_route` / `traceId` / `spanId` entries come from the
MDC ([§5.2](#52-mdc-keys)); the `adapter_*` key-values are the field family of [§5.1](#51-log-fields). The
`endpoint_request_id` in the example is not this module's: it is the ambient MDC of the inbound request
the call was made from (Limesium), which the additive emission scope leaves in place — this is how the
client line joins the server line without any coupling between the two libraries. How MDC entries land
in the document (flat, nested, renamed) is the encoder's decision.

With the optional arrival line enabled, a second, earlier line precedes it:

```
Client http exchange started POST https://api.example.com/things/42 [adapter_request_id=4bf92f…]
```

The arrival line carries no outcome, status or duration, so a dashboard keyed on `adapter_outcome` still
sees exactly one event per call.

### 1.4 The WebClient twin

The module is the **reference implementation** for the WebClient twin. It owns the cross-stack contract
files, and the twin's build binds them:

| Contract | Shipped here | Pinned in the twin by |
|---|---|---|
| Configuration keys and defaults | [`/docs/adapter-logging-reference.yml`](../../docs/adapter-logging-reference.yml) | `ClientLoggingReferenceConfigTest` in `legatium-common` (one `ClientLoggingProperties` class for both twins, bound against the YAML once) |
| Field family and index mapping | [`/docs/elk/…component-template.json`](../../docs/elk/README.md) | `ClientLogFieldTest` in `legatium-common` (one enum for both twins, locked against the template once) |
| Message text and meter names | this module's emitter and metrics | `TwinContractTest` in both modules |

The consequence for a consumer: a dashboard, alert or index mapping written for one client works
unchanged for the other — and a host that uses both clients (a servlet application with a `WebClient` for
streaming calls) may carry both modules, each logging the client it serves.

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
| `ClientLoggingAutoConfiguration` | Registers the interceptor bean, the default `NanoTimeSource` / `CorrelationIdGenerator`, and — when Boot's `spring-boot-restclient` is present — a late `RestClientCustomizer` and `RestTemplateCustomizer` that append the interceptor. |
| `ClientLoggingProperties` | The `adapter-logging.*` binding, validated in `init` - shared (legatium-common - §6.11), one class for both twins. `HeaderLogProperties` (shared too) is one header section with `includes` / `excludes` / `masked` and the masking fingerprint. |
| `ClientRequestLoggingInterceptor` | Owns the **client side**: activation by host and path, fail-open wiring, identity resolution (`traceparent` first, correlation header on traceless calls) with the traceless header, the request-body capture, the call-wide `MdcScope`, the breadcrumb, the no-response path, the handoff to the response wrapper. |
| `CapturingClientHttpResponse` | The response the client gets back: delegates, tees the body the application reads, reports a read failure, and turns `close()` into the emission point. |
| `Exchange` | Per-exchange state from entry to emission; the exactly-once guards. |
| `ExchangeLogEmitter` | Builds and emits the arrival line and the completion event; resolves level, outcome and cause (timeouts via the shared `Timeouts`); records body sizes; opens the emission `MdcScope` with trace ownership. |
| `ClientLogField` | The wire names and the exact JVM type of each structured field; a wrongly typed value drops the field with a warning, never the event. Shared (legatium-common): one enum for both twins. |
| `ClientLoggingMetrics` | The six meters - the fixed-tag meters pre-registered, the body meters created lazily per tag - with per-meter fallback to a private registry on registration conflict. |
| `BoundedBodyCapture` | The bounded capture target; count-only mode with limit `0`; the response-side read state (`BodyReadState`); single-writer/late-reader visibility via a volatile total. |
| `MdcScope` | Puts identity (and, for the emission, trace keys) into the MDC and restores the previous values on close. |
| `Traceparent` / `Timeouts` | Strict W3C `traceparent` parsing to `(traceId, spanId)`; the cause-chain walk that classifies a failure as a timeout. |
| `NanoTimeSource` / `CorrelationIdGenerator` / `HeaderValueMasker` | Injectable time, id and header masking; `SYSTEM` and the two `DEFAULT`s are the production defaults. |
| `reportQuietly` / `failOpen` | Guard the diagnostics channel (counter + internal log) of every catch block. |

### 2.2 Auto-configuration and registration

`ClientLoggingAutoConfiguration` is listed in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` and is conditional on
`adapter-logging.enabled` (default `true`) only — no web application type: a batch job that calls out is a
client too. It registers:

| Bean | Condition | Purpose |
|---|---|---|
| `NanoTimeSource` | `@ConditionalOnMissingBean` | `NanoTimeSource.SYSTEM` |
| `CorrelationIdGenerator` | `@ConditionalOnMissingBean` | `CorrelationIdGenerator.DEFAULT` (counting generator: random per-instance base-36 prefix + counter, 21 chars — ADR-0004) |
| `HeaderValueMasker` | `@ConditionalOnMissingBean` | `HeaderValueMasker.DEFAULT` (the `length:hash` fingerprint); the one bean both twins mask with |
| `ClientRequestLoggingInterceptor` | `@ConditionalOnMissingBean` | the interceptor, built from the bound properties and the host's `MeterRegistry` (`ObjectProvider`; private `SimpleMeterRegistry` without one) |
| `RestClientCustomizer` | `@ConditionalOnClass(RestClientCustomizer)`, `@Order(LOWEST_PRECEDENCE - 10)` | `builder.requestInterceptor(interceptor)` on every `RestClient.Builder` Boot hands out |
| `RestTemplateCustomizer` | `@ConditionalOnClass(RestTemplateCustomizer)`, same order | appends the interceptor to every `RestTemplate` built through `RestTemplateBuilder` |

Because the interceptor is its own bean, a host can replace it while keeping the customizers
([§3.4](#34-overriding-beans)). Boot's `spring-boot-restclient` module is an **optional** dependency:
without it the interceptor bean still exists and the host attaches it by hand.

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
trace ownership, see [§5.6](#56-trace-correlation)), selects the response headers, decodes the captured
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
   time ([§6.2](#62-duration-is-response-occupancy)).
2. Everything rests on the response being closed. The gauge `adapter.logging.exchanges.open` makes that
   assumption measurable ([§6.3](#63-a-response-that-is-never-closed)); the exactly-once CAS on
   `Exchange.completed` makes a double close harmless.

A call that produces **no response** — connection refused, DNS failure, a timeout before the status line
— emits right away from the interceptor's catch block: `-> -` in the message, no status field,
`adapter_outcome=failure` (or `timeout`, [§6.5](#65-timeouts-and-how-they-are-recognised)), the exception
attached as the cause. A short **WARN breadcrumb** with the exception's `toString` is logged first on the
module's own logger (`eu.inqudium.legatium.restclient.logging.ClientRequestLoggingInterceptor`) — not on
the exchange logger (one event per call is that stream's contract) — and the exception is rethrown
**unchanged** for the client to map (`ResourceAccessException` and friends).

### 2.5 The body tee

Bodies are never pre-read, buffered or replayed:

- The **request body** is what the interceptor is handed: `RestClient` and `RestTemplate` buffer the
  outgoing body into a byte array before the interceptor chain runs, so the capture simply copies (up to
  `max-body-bytes`) and counts it at wiring time. It is complete and final by construction; there is no
  read state on the request side.
- The **response body** is teed as the application reads it: `CapturingClientHttpResponse.getBody()`
  wraps the delegate's stream once; every `read` copies (up to the limit) and counts; an EOF return marks
  the body consumed to its end. Nothing is withheld, so streaming behaviour and the connection pool's
  view of the body are those of an unwrapped response.
- `BoundedBodyCapture` is the target: a `ByteArrayOutputStream` of at most `max-body-bytes` and a total
  byte counter. With limit `0` it runs in **count-only** mode for the body-size meters. Visibility from
  the reading thread to the closing thread (usually the same; not necessarily) is established by the
  capture itself: the volatile `totalBytes` is written last in every mutation.

The captures exist only when a body is logged (in any mode — `on-failure` needs the bytes before the outcome is known, [§4.3](#43-body-logging-and-body-measuring)) **or** measured; without either, the response wrapper still
exists (the close hook is the emission point), but the body stream passes through with the read-failure
guard only.

**The capture mirrors consumption, not transmission.** The log shows exactly the bytes the application
actually read — no more. A response body the application never opens (`toBodilessEntity()`, a
`ResponseEntity<Void>`) is logged as absent and records no size sample, even though the peer sent one; a
body read only partially is captured to exactly that extent, and the `[truncated, N bytes total]` note
counts what flowed, not `Content-Length`. This is the deliberate trade-off against a replaying buffer —
the log tells the truth about what the application processed, and streaming stays untouched. Because of
that, the log cannot tell a body the peer sent but the application dropped from one that was never sent;
the counter `adapter.response.body.read` ([§5.4](#54-meters)) exists for exactly that distinction.

### 2.6 MDC coverage

The module advertises "call identity in MDC while the wire call runs". Concretely:

| Thread / phase | Mechanism | Covered |
|---|---|---|
| The wire call (inner interceptors, the request factory, the HTTP engine's own logging) | call-wide `MdcScope` in `intercept` | yes |
| The body read and the close, after the interceptor returned | — the client's converters run in the caller's context | no (the caller's ambient MDC applies — usually the same thread, with its inbound identity) |
| The emission at close | `MdcScope` in the emitter, with trace ownership | yes |

`MdcScope` is an **additive overlay**: it puts the three `adapter_*` keys and restores the previous values
on close (threads are pooled; an inbound request's filter may own other keys). Around the call it leaves
the trace keys alone — a tracing bridge's own scope is authoritative there. Around the emission it
**owns** them: a parsed id is installed, an unparsed one is removed for the scope's lifetime, so a stale
bridge id on the closing thread can never join the event to a foreign trace.

The one thing the overlay never does is *replace*: `endpoint_request_id` (Limesium) and every other
ambient key stay visible on the client line, which is how inbound and outbound lines join without either
library knowing about the other.

### 2.7 Fail-open contract

A logging component must never fail the call it describes. The module enforces that at every boundary
where it calls host-provided code (MDC adapter, appenders, `MeterRegistry`, the client's request and
response objects):

| Stage | Where | What happens on failure | Counted as |
|---|---|---|---|
| wiring | `wireExchange` (correlation bean, header selection, capture construction) | the interceptor degrades to a plain pass-through for this call | `failopen{stage=wiring}` |
| wiring | `MdcScope` open | the call runs without call MDC | `failopen{stage=wiring}` |
| wiring | `MdcScope` close | restoration lost; never masks an exception propagating out of the call | `failopen{stage=wiring}` |
| wiring | body-size recording, operational counter updates | the event follows without the sample / the count | `failopen{stage=wiring}` |
| arrival | `logRequestStart` (including the level gate) | the arrival line is dropped | `failopen{stage=arrival}` |
| emission | `logExchange` — everything after the exactly-once CAS, including the status read | the exchange event is **lost**; the close returns normally | `failopen{stage=emission}` |
| registration | `ClientLoggingMetrics.registerOrFallback` | the conflicting meter lives in a private registry, warned once per name | — |

Every catch block reports through `reportQuietly`, which swallows a failure of the diagnostics channel
itself (a throwing `Counter`, a throwing appender that also covers the internal logger).
`InterruptedException` is caught separately and the interrupt flag is restored.

Failures of the logging are reported on the module's **own** loggers
(`eu.inqudium.legatium.restclient.logging.ClientRequestLoggingInterceptor`, `…ExchangeLogEmitter`,
`…ClientLoggingMetrics`), never on the exchange logger, so the exchange stream stays parseable.

**Security note.** Fail-open is the inverse of what an audit log needs: a host-side fault silently
removes the call from the log instead of failing it. The exchange log is therefore an **observability**
feature with no completeness guarantee; a regulatory audit trail of outbound calls must come from a
fail-closed component. The compensating controls are `adapter.logging.failopen` and the
`exchanges.open` gauge ([§5.5](#55-reading-the-meters-together)) — alert on them.

### 2.8 Injectable collaborators

Time and randomness are injected, not ambient:

- `NanoTimeSource` — monotonic nanoseconds for `adapter_duration_ms` and the slow threshold; the single
  production read of `System.nanoTime()` is `NanoTimeSource.SYSTEM`. Log timestamps come from the
  logging backend, keeping the two time domains separate.
- `CorrelationIdGenerator` — the id for traceless calls without a correlation header; `DEFAULT` (the
  counting generator, ADR-0004) by default. Never consulted for a traced call (ADR-0002: the
  `traceparent` trace id is the request id) — and in a host with tracing configured, never at all
  ([§6.8](#68-tracing-makes-every-call-traced)).

- `HeaderValueMasker` — how a header listed in a `masked` section renders on the line; `DEFAULT` is the
  stable `length:hash` fingerprint ([§6.10](#610-masking-is-a-fingerprint-not-a-secret)). The
  properties decide WHICH values are masked, the bean decides HOW - a keyed HMAC for a compliance regime,
  a fixed `***` for a host that wants no correlation at all.

All three are `fun interface`s, all three are `@ConditionalOnMissingBean` beans, and all are what the
module's tests drive from an `AtomicLong` / a fixed string / a lambda without any mocking library.

---

## 3. Using it in a foreign project

### 3.1 Prerequisites

| Requirement | Notes |
|---|---|
| Spring Boot 4.x application using `RestClient` and/or `RestTemplate` | any application type — servlet, reactive, or none; the module is a client-side library |
| Boot's `spring-boot-restclient` (via `spring-boot-starter-restclient` or transitively) | provides `RestClient.Builder`, `RestTemplateBuilder` and the customizer contracts the auto-configuration hooks; **optional** for the module — without it the interceptor bean is attached by hand |
| Java 21, Kotlin stdlib | the module is written in Kotlin; a Java host only needs `kotlin-stdlib`, which the jar pulls transitively |
| SLF4J 2.x binding (Logback by default in Boot) | the module uses the fluent `LoggingEventBuilder` API (`addKeyValue`) |
| Micrometer core | present via any Boot starter; an actuator `MeterRegistry` is optional |
| An HTTP engine | whatever the host's request factory uses (JDK `HttpClient` by default, Apache HttpComponents, ...) — the module is engine-agnostic |

The module is a **library**, not a starter: it declares `spring-boot-autoconfigure`, `slf4j-api`,
`spring-web`, `micrometer-core`, `kotlin-stdlib` and the optional `spring-boot-restclient` — no logging
backend, no YAML, no HTTP engine are forced onto the host.

### 3.2 Adding the dependency

```xml
<dependency>
    <groupId>eu.inqudium</groupId>
    <artifactId>legatium-restclient-logging</artifactId>
    <version><!-- current release: see the badge below --></version>
</dependency>
```

The current release is shown live by the Maven Central badge:
[![Maven Central](https://img.shields.io/maven-central/v/eu.inqudium/legatium-restclient-logging.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/eu.inqudium/legatium-restclient-logging)

That is all: the auto-configuration registers the interceptor and the customizers, every call through a
Boot-built `RestClient` or `RestTemplate` is logged on the `http-adapter-exchange` logger at INFO, the
request id comes from the `traceparent` trace id (traceless calls send an `X-Correlation-Id` instead —
ADR-0002), the `adapter_*` keys are in the MDC for the wire call, and the six meters are registered in
the host's `MeterRegistry` if one exists.

To remove the module again without touching the classpath:

```yaml
adapter-logging:
  enabled: false
```

### 3.3 Interceptor order and other interceptors

The customizers are ordered at `Ordered.LOWEST_PRECEDENCE - 10`, so the interceptor is appended **behind**
the interceptors of earlier customizers and of the builder's own configuration, and runs **inside** them —
closest to the wire:

- an authentication interceptor outside it has already added its header, so the logged (and masked)
  request headers are what the peer receives;
- a retrying interceptor outside it invokes it once per attempt — one line per attempt, each an honest
  crossing ([§6.7](#67-retries-yield-one-line-per-attempt));
- interceptors a host adds **after** the customizers ran (directly on a builder it obtained from Boot)
  run inside this one and are outside that guarantee — they see the request after this interceptor did.

The `traceparent` header is not affected by the order at all: the client observation Boot registers
injects it into the request **before** any interceptor runs ([§5.6](#56-trace-correlation)).

Activation is evaluated **in the interceptor** (`shouldNotFilter`), so its semantics are byte-identical
with the WebClient twin. If the host needs a different position, it disables the customizers by defining
its own `RestClientCustomizer` ordering or attaches the bean itself.

### 3.4 Overriding beans

Every default is `@ConditionalOnMissingBean`:

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

A host-defined `ClientRequestLoggingInterceptor` bean replaces the **interceptor**, not the wiring: the
auto-configured customizers still attach it to every Boot-built client. The constructor takes
`(ClientLoggingProperties, NanoTimeSource, CorrelationIdGenerator, MeterRegistry)` plus an optional
trailing `HeaderValueMasker` (the built-in fingerprint when omitted):

```kotlin
@Bean
fun clientRequestLoggingInterceptor(
    properties: ClientLoggingProperties,
    nanoTime: NanoTimeSource,
    ids: CorrelationIdGenerator,
    registry: MeterRegistry,
): ClientRequestLoggingInterceptor = ClientRequestLoggingInterceptor(properties, nanoTime, ids, registry)
```

A client built **by hand** (`RestClient.builder()` without Boot's builder bean, a `RestTemplate` constructed
directly) receives the interceptor from the host:

```kotlin
val client = RestClient.builder().baseUrl(url).requestInterceptor(interceptor).build()
```

Keep in mind the one-instance-per-registry rule of the gauge ([§6.9](#69-one-metrics-instance-per-registry)).

### 3.5 Logging backend and structured output

The module emits through SLF4J's fluent API. Every exchange event carries its data in **two places**, and
an encoder treats them differently:

| Data | Carried as | Examples |
|---|---|---|
| The field family | SLF4J **key-value pairs** (`addKeyValue`) | `adapter_outcome`, `adapter_duration_ms`, `adapter_url_host`, `adapter_response_body` |
| The identity and trace context | **MDC** entries, set by the emission scope (and, for the call, by the call scope) | `adapter_request_id`, `adapter_method`, `adapter_route`, `traceId`, `spanId` (from the `traceparent` header) |

A plain `%msg` pattern shows neither — only the message, which repeats the gist inline
(`… -> 200 [adapter_request_id=…]`) precisely for that case. Logback offers three ways to render the
rest; which one fits depends on where the output goes.

#### Option 1 — `PatternLayout` with `%kvp` and `%mdc` (text, for terminals and files)

```xml
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
        <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg %kvp{NONE} [%mdc]%n</pattern>
    </encoder>
</appender>
```

```
13:54:58.534 INFO  [http-nio-8080-exec-3] http-adapter-exchange - Client http exchange POST https://api.example.com/things/42 -> 200 [adapter_request_id=4bf9… traceId=4bf9… spanId=00f0…] adapter_outcome=success adapter_duration_ms=17 adapter_request_method=POST adapter_response_status_code=200 adapter_url_host=api.example.com adapter_url_path=/things/42 adapter_url_template=https://api.example.com/things/{id} [adapter_method=POST, adapter_request_id=4bf9…, adapter_route=https://api.example.com/things/42, endpoint_request_id=4bf9…, traceId=4bf9…, spanId=00f0…]
```

- `%kvp` quotes values with double quotes by default; `%kvp{NONE}` leaves them bare.
- `%X{adapter_request_id:-}` prints one key and nothing when it is absent; `%mdc` prints every entry
  that is present as `key=value`, so the trace keys appear only on traced calls.
- In Spring Boot the same pattern goes into `logging.pattern.console` without any XML.
- This is the module's own test configuration (`src/test/resources/logback-test.xml`).
- **Text output renders values raw.** The logged path and query are percent-encoded as sent, but bodies
  (opt-in) may contain line breaks — mind that before pointing a text appender at a pipeline that parses
  lines.

#### Option 2 — Logback's `JsonEncoder` (JSON without an extra dependency, Logback ≥ 1.4.3)

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

#### Option 3 — Spring Boot structured logging (JSON, flat, typed — recommended for an index)

```yaml
logging:
  structured:
    format:
      console: ecs      # or logstash, gelf
  level:
    http-adapter-exchange: INFO
    eu.inqudium.legatium.restclient.logging: WARN
```

Key-value pairs and MDC entries become **flat top-level fields**, and values keep their JVM type —
`adapter_duration_ms` is a number, `adapter_response_status_code` a number, which is what the type
assertion in `ClientLogField` guarantees on the producing side. This is the shape the component template
in [§3.6](#36-index-mapping-elk) is written for. `logging.structured.json.include` / `exclude` / `rename`
control the field selection (e.g. to drop `adapter_route`, which duplicates host and path).

| Option | Output | Key-value pairs | MDC | Typed values | Escapes control chars | Use for |
|---|---|---|---|---|---|---|
| 1 `PatternLayout` `%kvp` `%mdc` | text | inline `k=v` | inline `k=v` | no (all text) | **no** | terminals, local files, tests |
| 2 `JsonEncoder` | JSON | list of objects | nested `mdc` | partly | yes | local JSON inspection |
| 3 `StructuredLogEncoder` | JSON | flat fields | flat fields | **yes** | yes | **log index (ELK etc.)** |

Whatever the option, keep the `eu.inqudium.legatium.restclient.logging` logger at WARN or lower: it
carries the WARN breadcrumb on a thrown call and the module's own failure reports.

### 3.6 Index mapping (ELK)

The thirteen `adapter_*` fields have a ready-made Elasticsearch component template in
[`/docs/elk/`](../../docs/elk/README.md):

```bash
curl -X PUT "$ES/_component_template/legatium-restclient-logging-fields" \
     -H 'Content-Type: application/json' \
     --data-binary @docs/elk/legatium-restclient-logging-fields.component-template.json
```

Compose it into the data-stream mapping **before** the first event arrives — an unmapped body or header
field would be mapped dynamically and become searchable, which the payload fields' `index: false`
deliberately prevents. The MDC-carried keys are intentionally not in the template: where they land
depends on the host's encoder layout; map them where the encoder configuration lives. The template
composes beside Limesium's `endpoint_*` template without collision.

### 3.7 Verifying the integration

1. Make any call through a Boot-built `RestClient`:

   ```kotlin
   restClientBuilder.baseUrl("https://httpbin.org").build().get().uri("/get").retrieve().body(String::class.java)
   ```

   Expect one `http-adapter-exchange` line with `adapter_request_id=…`. Without tracing configured, the
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

---

## 4. Configuration

All properties live under `adapter-logging.*`. The complete, commented reference with every default is
[`/docs/adapter-logging-reference.yml`](../../docs/adapter-logging-reference.yml);
`ClientLoggingReferenceConfigTest` (in `legatium-common`) binds it against the shared
`ClientLoggingProperties` and fails the build on any drift — every key must exist, every value must be
the built-in default. Both twins bind that one class, so the namespace is identical across the stacks
by construction.

### 4.1 Property reference

| Property | Type | Default | Meaning |
|---|---|---|---|
| `enabled` | boolean | `true` | Master switch. `false` makes the auto-configuration back off — no interceptor, no customizers, no beans. A context-start decision, not a runtime toggle. |
| `logger-name` | string | `http-adapter-exchange` | Logger of the arrival line and the exchange event. Its level is the runtime volume control ([§4.5](#45-logger-levels)). Distinct from Limesium's `http-exchange` by design. |
| `correlation-id-header` | string (RFC 9110 token) | `X-Correlation-Id` | Header read from a **traceless** request (no conformant `traceparent` — ADR-0002); when absent or blank, an id is generated and ADDED to the request under this name so the peer can quote it. A traced call takes its request id from the `traceparent` trace id, ignores this header and adds nothing. |
| `include-query-string` | boolean | `true` | Log the query string as its own field `adapter_url_query` (never part of the path). Disable when query parameters may carry personal data. |
| `log-request-start` | boolean | `false` | Additionally log an arrival line before the wire call, at INFO, under the emission MDC. Carries no outcome/status/duration. |
| `include-path-patterns` | list of `PathPattern` | `[]` | Request paths the interceptor is active for at all, whatever the host; empty = every call. Parsed once at startup; an invalid pattern fails the context. |
| `exclude-path-prefixes` | list of strings | `[]` | Request-path prefixes the interceptor skips entirely — no event, no MDC, no correlation header, no gauge movement. Prefix match against the decoded path. An exclude always wins over an include. |
| `exclude-hosts` | list of strings | `[]` | Peer hosts the interceptor skips entirely (case-insensitive, without port) — the outbound counterpart of excluding a health probe. |
| `slow-request-threshold` | duration | `5s` | At/above this duration an INFO call escalates to WARN and is flagged `adapter_slow: true`; the outcome stays `success`. Measured as response occupancy ([§6.2](#62-duration-is-response-occupancy)). Must be ≥ 1 ms. |
| `request-headers.includes` / `.excludes` / `.masked` | lists of header names | `[]` | See [§4.2](#42-header-sections). |
| `response-headers.includes` / `.excludes` / `.masked` | lists of header names | `[]` | See [§4.2](#42-header-sections). |
| `log-request-body` | `never` \| `on-failure` \| `always` | `never` | Log the request body the client hands the interceptor into `adapter_request_body`, up to `max-body-bytes` — on every line (`always`) or only when the outcome is not `success` or the status is a 4xx (`on-failure`, [§4.3](#43-body-logging-and-body-measuring)). |
| `log-response-body` | `never` \| `on-failure` \| `always` | `never` | Tee the response body into `adapter_response_body` as the application reads it, up to `max-body-bytes` — on every line or only when the outcome is not `success` or the status is a 4xx. |
| `measure-request-body-size` | boolean | `false` | Record `adapter.request.body.size`; independent of `log-request-body`. |
| `measure-response-body-size` | boolean | `false` | Record `adapter.response.body.size` and `adapter.response.body.read`; independent of `log-response-body`. |
| `max-body-bytes` | int > 0 | `16384` | Capture limit per body. Bounds **memory**, not the exchange: bytes beyond it still flow; the logged value is truncated with a note of the total size. |
| `masking-key` | string | *(empty)* | Keys the masking fingerprint: empty keeps the unkeyed `length:hash`, any other value turns it into an HMAC-SHA256 under the key — same shape, same stability under the same key, guess-proof without it. A **secret**: supply it like one; the properties' `toString` redacts it. Ignored when a host pins its own `HeaderValueMasker` bean. |

### 4.2 Header sections

Each direction has one section with four lists; matching is case-insensitive throughout. The section
is **masked by default** (ADR-0005): whatever it logs is rendered as a fingerprint unless the name is
explicitly allowed in plaintext, so the debugging move `includes: ["*"]` costs readability, never
confidentiality.

| List | Semantics |
|---|---|
| `includes` | Names to log. **Empty logs nothing** (the safe default). The entry `*` logs every header the message carries, deduplicated case-insensitively. |
| `excludes` | Names removed from the included set — meaningful mainly with `*`. An exclude always wins. `*` is rejected here at binding time (an empty `includes` already logs nothing). |
| `masked` | Names whose **value** is replaced by what the `HeaderValueMasker` bean renders — by default a fingerprint `length:hex`, the character length plus the first 64 bits of the SHA-256 of the UTF-8 value, e.g. `18:930bbdc51b6aed5c` (a **pseudonym**, not anonymisation: equal values stay recognisable as equal; key it with `masking-key` to stop guess confirmation). **Default `["*"]`: every logged header is masked** (ADR-0005). Narrow it to names, or empty it to switch masking off — a visible decision. Masking affects only headers that are logged; listing a name here does not include it. |
| `unmasked` | Names that appear in **plaintext** although `masked` covers them — the explicit allowlist of harmless names (`Content-Type`, `Accept`, a correlation id). An unmasked name always wins over a masked one. `*` is rejected here: the plaintext set is a list of names by design; to log everything in plaintext, empty `masked` instead. |

Multi-valued headers are joined with `, `. The selected pairs are rendered into one display-only field
per direction as `[Name:"value", Name2:"value2"]`; nothing is emitted when the selection is empty or no
selected header is present.

Request headers are selected at **wiring time**, after the correlation header was added, so a selected
`X-Correlation-Id` shows what actually went out; response headers at **close**, so they reflect the
response as received.

### 4.3 Body logging and body measuring

Per direction, a **mode** decides whether a body is logged and a **flag** decides whether its size is
measured — independent of each other:

| `log-*-body` | `measure-*-body-size` | Capture installed | Buffered | Effect |
|---|---|---|---|---|
| `never` | off | no | — | request body untouched; response stream passes through (read-failure guard only) |
| `always` | off | yes, limit `max-body-bytes` | up to the limit | field logged on every line; no size sample |
| `on-failure` | off | yes, limit `max-body-bytes` | up to the limit | field logged only when `adapter_outcome` is not `success` or the status is a 4xx; no size sample |
| `never` | on | yes, limit `0` (count-only) | nothing | size sample recorded; no field |
| `always` / `on-failure` | on | yes, limit `max-body-bytes` | up to the limit | both |

**`on-failure` is the volume switch** ([ADR-0006](../../docs/adr/ADR-0006-bodies-logged-by-outcome.md)).
`always` means every body of every call; what is nearly always wanted is bodies for the calls that went
wrong — `failure`, `timeout` — which cuts the volume by orders of magnitude and hits exactly the
lines a body is wanted for. The response side decides at emission, when the outcome is final. The request
body flows before the outcome is known, so `on-failure` captures it exactly like `always` does (bounded by
`max-body-bytes`) and discards it for a success: the capture is paid, the output is saved — and the output
is what burdens the log pipeline. The gate is wider than the outcome vocabulary ([§5.3](#53-levels-and-outcomes)) by one status
class: a `4xx` answer keeps its `success` outcome — the peer answered — but its bodies are logged in
`on-failure`, because the client's error is exactly what the body explains; a `5xx` is `failure` and logs as
well. A slow but healthy call stays `success` and logs no bodies.

Rules that hold for every combination:

- The request body is copied from the byte array the client hands over — complete and final; the
  response tee is passive: bytes are counted and (up to the limit) copied as the application reads them.
- An **unread response body** is logged as absent; no size sample is recorded.
- Zero-byte bodies produce no field and no sample — the distribution describes bodies that exist.
- Truncation is **byte-bounded**, and the decoder leaves an incomplete trailing multi-byte sequence
  undecoded rather than rendering a replacement character: `…<prefix>... [truncated, 12345 bytes total]`.
- The log charset is the one the `Content-Type` declares (request: the caller's; response: the peer's),
  UTF-8 when absent or unparsable.
- `measure-*` records what actually flowed, **exact beyond** `max-body-bytes`.
- `measure-response-body-size` additionally records `adapter.response.body.read` — whether the application
  consumed the body completely, partially, or not at all ([§5.4](#54-meters)).

### 4.4 Activation: hosts and paths

```
active(uri) = uri.host not in exclude-hosts
              AND (include-path-patterns is empty  OR  any pattern matches uri.path)
              AND no exclude-path-prefix is a prefix of uri.path
```

An inactive call passes through **without any trace**: no correlation header, no MDC, no event, no gauge
movement, no counters. Typical use:

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

### 4.5 Logger levels

Severity and semantic are decoupled: the level only decides how loud — and whether — a line is emitted;
`adapter_outcome` carries the disposition ([§5.3](#53-levels-and-outcomes)). The level of the
`logger-name` logger therefore acts as the runtime volume control:

| `http-adapter-exchange` level | Emitted |
|---|---|
| `INFO` | every call |
| `WARN` | failures (5xx), timeouts, slow calls — and thrown calls |
| `ERROR` | only calls that threw (no response, or the body read failed) |
| `OFF` | nothing — and no event is even assembled |

Level and outcome are resolved **before** the event is built, so a disabled level costs no assembly, no
header selection, no body decoding. Metrics are recorded **before** the level gate and are unaffected by
it — except `adapter.logging.events`, which by definition counts emitted events only.

### 4.6 Validation at startup

`ClientLoggingProperties.init` and `HeaderLogProperties.init` reject, with a message naming the property:

- blank `logger-name` or `correlation-id-header`;
- a `correlation-id-header` that is not an RFC 9110 token (it is written onto every traceless request; an
  engine that validates field names — the JDK client does — would reject a non-token per call, failing
  the CALL, not merely the log line);
- `max-body-bytes` ≤ 0;
- a blank (whitespace-only) `masking-key` - empty means unkeyed, whitespace is a worthless secret;
- `slow-request-threshold` < 1 ms (the logged duration has millisecond resolution);
- blank entries in any list (`exclude-hosts` included);
- `*` in an `excludes` or an `unmasked` list;
- an unparsable `include-path-patterns` entry (parsed once at interceptor construction).

### 4.7 Example configurations

**Minimal production profile** — everything logged, telemetry peers excluded, slow threshold tightened:

```yaml
adapter-logging:
  exclude-hosts:
    - pushgateway.monitoring.svc
  slow-request-threshold: 2s
logging:
  level:
    http-adapter-exchange: INFO
    eu.inqudium.legatium.restclient.logging: WARN
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
buffered up to `max-body-bytes` per call and dropped on success:

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

## 5. Metrics and observation

### 5.1 Log fields

The structured fields of the completion event (the arrival line carries method, host, path, template,
query and request headers without outcome/duration/status). The index types are those of the shared
component template; `ClientLogFieldTest` in `legatium-common` keeps the shared enum in lockstep with it.

| Field | Type | Index | doc_values | When present | Notes |
|---|---|---|---|---|---|
| `adapter_outcome` | keyword | yes | on | always | `success` / `failure` / `timeout` — the field dashboards split by; decoupled from the level |
| `adapter_duration_ms` | long | yes | on | always | from the injected monotonic source; until response close |
| `adapter_request_method` | keyword | yes | on | always | |
| `adapter_response_status_code` | short | yes | on | when a response arrived | absent for a refused connection or a timeout before the status line (`-> -`) |
| `adapter_url_host` | keyword | yes | on | when the URI has a host | `host` or `host:port` — the outbound coordinate |
| `adapter_url_template` | keyword | yes | on | when `RestClient` recorded a template | the aggregation half of the path pair, e.g. `https://api.example.com/things/{id}` |
| `adapter_url_path` | keyword | yes | **off** | always | the **raw** path as sent, ids and all — filter exactly, never group |
| `adapter_url_query` | keyword | yes | **off** | when the request had one and `include-query-string` is on | raw, as sent |
| `adapter_slow` | boolean | yes | on | only when the threshold was reached | absence means fast |
| `adapter_request_headers` | keyword | **no** | off | when selected headers are present | display only, rendered `[Name:"value", …]` |
| `adapter_response_headers` | keyword | **no** | off | when selected headers are present | display only |
| `adapter_request_body` | keyword | **no** | off | when `log-request-body` admits the outcome and bytes were sent | display only, bounded |
| `adapter_response_body` | keyword | **no** | off | when `log-response-body` admits the outcome and bytes were read | display only, bounded |

Each field asserts the exact JVM type of its value (`ClientLogField.format`): a wrongly typed value
drops **that field** with a warning on `eu.inqudium.legatium.common.ClientLogField`, never the event.

The throwable of a failed call is attached to the event as its cause (`setCause`), so a structured
encoder renders the stack trace alongside the fields.

### 5.2 MDC keys

Set by `MdcScope` around each emission and around the wire call ([§2.6](#26-mdc-coverage)):

| Key | Value | Scope |
|---|---|---|
| `adapter_request_id` | the request id: the `traceparent` trace id, or the accepted/generated correlation id (ADR-0002) — always set | call; emission |
| `adapter_method` | the HTTP method | same |
| `adapter_route` | the request **target**: `scheme://host[:port]/path`, query excluded — for an outbound call the host is as much part of the route as the path | same |
| `traceId` | trace id from `traceparent` | emission only (owned) |
| `spanId` | parent-id from `traceparent` — the **local client span** the peer will see as its parent | emission only (owned) |

`MdcScope` restores the previous value of every key on close, rolls back a partial install if the
adapter throws mid-put, and restores best-effort on close with the first failure rethrown and later ones
suppressed. It never removes keys it does not own: an inbound request's identity stays.

### 5.3 Levels and outcomes

Resolved in this order in `ExchangeLogEmitter`:

| Condition | Level | `adapter_outcome` |
|---|---|---|
| the call threw and a timeout is in the cause chain ([§6.5](#65-timeouts-and-how-they-are-recognised)) | `WARN` | `timeout` |
| the call threw (no response), or the body read threw | `ERROR` | `failure` |
| status ≥ 500 without an exception (the peer answered; the application decides) | `WARN` | `failure` |
| otherwise | `INFO` | `success` |
| … and the duration reached `slow-request-threshold` | `INFO → WARN` | unchanged, plus `adapter_slow: true` |

A 4xx is a `success` at INFO: the peer answered as designed, and whether a 404 is a problem is the
application's call — the status is on the line for the dashboard to split by. Slowness raises severity;
it never turns a completed call into a failure.

### 5.4 Meters

Six meters, all **consumed** from the host's `MeterRegistry` (an `ObjectProvider`; without one a private
`SimpleMeterRegistry` absorbs the values). All fixed-tag meters are **pre-registered at construction**,
so a `rate()` alert sees the zero before the first occurrence. Rates, latencies and status distributions
are deliberately left to `http.client.requests` and the log fields.

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `adapter.logging.failopen` | counter | `stage` = `emission` \| `arrival` \| `wiring` | Logging failures the fail-open path swallowed. `emission`: an exchange event was **lost**. `arrival`: a start line was lost. `wiring`: bookkeeping failed (pass-through degradation, a lost sample or counter) — the event usually still follows. A lost log line cannot report itself through the same pipeline; this counter is the independent channel. |
| `adapter.logging.events` | counter | `outcome` = `success` \| `failure` \| `timeout` | Exchange events actually **emitted** on the exchange logger — after the level gate, arrival lines excluded. The reconciliation ground truth against the log index. |
| `adapter.logging.exchanges.open` | gauge | — | Exchanges between interceptor entry (wiring) and response close. Hovers near the in-flight call count in health. |
| `adapter.logging.correlation.id` | counter | `source` = `trace` \| `header` \| `generated` | Origin of each call's request id (ADR-0002). |
| `adapter.response.body.read` | counter | `uri` = template, `UNKNOWN` without one; `host`; `state` = `unread` \| `partial` \| `complete` | How far the application **consumed** the response body, opt-in via `measure-response-body-size`. Recorded once per call that received a response — including bodiless consumption (`toBodilessEntity`), which is the `unread` share the counter exists to show. `partial` = the stream was opened but EOF was never observed (a converter that stopped early, an exception mid-read). Created lazily per tag set on first use. |
| `adapter.request.body.size` / `adapter.response.body.size` | distribution summary, base unit `bytes` | `uri`, `host` | Bytes that **actually flowed**, opt-in via `measure-*-body-size`, independent of body logging and level. Exact beyond `max-body-bytes`. Zero-byte bodies record no sample. Created lazily per tag set on first use. |

**Registration conflicts.** Micrometer rejects a registration whose id already exists with a different
meter type. Rather than aborting the context (at construction) or suppressing an exchange event (at the
lazy body-size registration), the conflicting meter falls back to a private registry, warned once per
meter name on `eu.inqudium.legatium.restclient.logging.ClientLoggingMetrics`: the module keeps working and
that meter is simply not exported.

### 5.5 Reading the meters together

The meters are designed to cover each other's blind spots:

| Question | Signal |
|---|---|
| Are exchange events being lost **loudly** (something threw)? | `failopen{stage=emission}` > 0 |
| Are exchange events being lost **silently** (nothing threw, a response was never closed)? | `exchanges.open` baseline grows monotonically instead of returning towards 0 |
| Is the **log pipeline** (appender, broker, index) losing events? | `sum(adapter.logging.events)` over a window ≠ count of indexed `http-adapter-exchange` documents for the same window |
| Did the application stop propagating identity onto its calls? | the `generated` share of `correlation.id` rises (in a host with tracing configured it is zero by construction — [§6.8](#68-tracing-makes-every-call-traced)) |
| Is a call site discarding the payload it paid for? | the `unread` or `partial` share of `response.body.read{uri=...,host=...}` rises |
| Are payloads growing beyond what the log captures? | `body.size` percentiles vs. `max-body-bytes` |
| Which dependency is slow, or failing? | `adapter_url_host` on the log line, split by `adapter_outcome` — not a meter of this module; `http.client.requests` has the latency histogram |

A suggested alert set:

```promql
# lost exchange events (hard failure)
increase(adapter_logging_failopen_total{stage="emission"}[5m]) > 0

# silently stuck exchanges (liveness) - tune the bound to the service's outbound concurrency
min_over_time(adapter_logging_exchanges_open[15m]) > 50

# timeouts per peer (from the log index, not a meter): adapter_outcome=timeout by adapter_url_host
```

Note on the gauge: a response the application holds open deliberately (a streaming download) is
**intended** to stay open — that is the liveness signal working, not a leak to suppress
([§6.3](#63-a-response-that-is-never-closed)).

### 5.6 Trace correlation

The module reads the **outgoing W3C `traceparent` header**, put on the request by the host's tracing
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

**Where the header comes from.** With Micrometer Tracing on the classpath, Boot's
`RestClientObservationAutoConfiguration` (resp. the `RestTemplate` variant) registers the client
observation; when the observation starts, the tracing handler opens the client span and **injects
`traceparent` into the request headers** — and `RestClient` starts the observation *before* the
interceptor chain runs, so every interceptor, this one included, sees the header. The tracing integration
test pins that order beside a real Brave bridge; a Boot upgrade that changes it breaks the build rather
than silently dropping trace ids.

---

## 6. Special characteristics

### 6.1 Differences to the WebClient twin

Everything not listed here behaves exactly as in `legatium-webclient-logging`.

| Concern | This module | WebClient twin |
|---|---|---|
| Disposition vocabulary | `success` / `failure` / `timeout` | plus **`cancelled`** — a cancelled subscription (a downstream `timeout()` operator, a `take`, a disposed caller) is the reactive reality a blocking call cannot have |
| Emission point | response **close** | the response **body's terminal signal** |
| Never-completing exchange | a response the application never closes | a response body nobody subscribes to or releases |
| Request body | the byte array the client hands the interceptor — complete, captured at wiring | teed at the connector's `writeWith` as the inserter writes it |
| Call-wide MDC | thread-local, for the wire call | none — the call hops event-loop threads; emission MDC and the message inline only |
| Read failure mid-body | `IOException` from the tee stream, reported and rethrown | the body `Flux`'s error signal |
| URI template | recorded by `RestClient`; **never** by `RestTemplate` | recorded by `WebClient` |
| Attachment | `RestClientCustomizer` + `RestTemplateCustomizer` | `WebClientCustomizer` |

### 6.2 Duration is response occupancy

`adapter_duration_ms` runs from wiring (before the wire call) to response close (after the body was read
and the client was done). A peer that answers the status line fast but streams the body slowly is slow
by this measure — which is the truth an operator wants, and the same rule Limesium applies inbound
(request occupancy). Bare round-trip latency is what `http.client.requests` already measures.

### 6.3 A response that is never closed

The emission rests on the response being closed. `RestClient`'s `retrieve()`, `body(...)`, `toEntity(...)`
and `exchange(..., close = true)` (the default) close in a `finally`; `RestTemplate` closes in
`doExecute`'s `finally`. The one path that does not is a raw `exchange(fn, close = false)` — or a
streaming result (`InputStreamResource`, a `ResponseEntity<InputStream>`) where `RestClient` deliberately
hands the open stream to the caller. There the caller owns the close, and until it happens the exchange
stays **open on the gauge** `adapter.logging.exchanges.open`. A monotonically growing baseline is the
signal that responses are leaking — a resource leak in the host, visible through the module's liveness
meter before it becomes a pool exhaustion.

### 6.4 Failures while reading the body

The status line arrived, then the connection died mid-body (a reset, a read timeout while streaming). The
tee reports the `IOException` to the exchange and rethrows it unchanged; at close the event is
`adapter_outcome=failure` (or `timeout`) **with the status that was received** — "200 but failed" is exactly
what happened, and hiding either half would mislead. The captured prefix of the body is logged as far as
it flowed.

### 6.5 Timeouts and how they are recognised

A timeout is the one client-side disposition an operator reads differently from every other failure (the
peer is slow, not broken), so it has its own outcome value at WARN. The shared `Timeouts` classification
walks the exception's **cause chain** and, per link, the class hierarchy by name: the JDK's
`SocketTimeoutException`, `java.net.http.HttpTimeoutException` (and its connect subtype) and
`java.util.concurrent.TimeoutException` are matched as types; Netty's `io.netty.handler.timeout.
TimeoutException` family by its fully qualified name, so the WebClient twin recognises a Reactor Netty
read timeout without a Netty dependency here. Engines wrap their timeouts (`IOException` over
`SocketTimeoutException`; `RestClient` wraps once more into `ResourceAccessException` *after* the
interceptor saw the original) — hence the chain walk. Anything else is a plain `failure`.

### 6.6 RestTemplate has no URI template

`RestClient` records the URI template of a call made through `uri(String, Object...)` as a request
attribute (`org.springframework.web.client.RestClient.uriTemplate`, mirrored by the module and pinned
against the client by `UriTemplateAttributeTest`) — that is `adapter_url_template`, the low-cardinality
aggregation half of the path pair. `RestTemplate` sets its template only on the observation context, not
on the request, so `RestTemplate` calls log the path alone and their body meters fall under
`uri=UNKNOWN`. A host that wants the template on `RestTemplate` calls migrates to `RestClient`; the
module does not reconstruct templates by guessing.

### 6.7 Retries yield one line per attempt

The interceptor sits innermost ([§3.3](#33-interceptor-order-and-other-interceptors)), so a retrying
interceptor (or a resilience decorator around the client) invokes it once per attempt. Each attempt is a
crossing and gets its own line — with the same `adapter_request_id` under a trace, or a **new** generated
id per attempt on a traceless call (each attempt wires afresh and the retried request already carries the
first attempt's correlation header only if the retrying layer reuses the mutated request object; a rebuilt
request gets a new id). Dashboards counting calls per peer count attempts; `http.client.requests` does
the same.

### 6.8 Tracing makes every call traced

With Micrometer Tracing configured, the client observation roots a trace whenever none is active, so
**every** outbound call carries a `traceparent` — sampled or not (an unsampled trace still propagates,
with flags `00`). Consequences: the module never generates a correlation id in such a host,
`correlation.id{source=generated}` reads zero by construction, and the peer never receives an
`X-Correlation-Id` from this module. A peer without tracing that needs a quotable id in that setup is a
matter for the host's propagation configuration (baggage), not for this module — which stays neutral.
Pinned by the tracing integration test.

### 6.9 One metrics instance per registry

Micrometer deduplicates meters by id. A second `ClientLoggingMetrics` instance against the same registry
would share the **counters** (increments merge) but not the **gauge**: the second gauge registration is
silently ignored. Every interceptor therefore obtains its metrics owner through a per-registry cache, so
several interceptors on one registry (a host wiring extra instances by hand) share one owner and the
gauge reports the total across them.

### 6.10 Masking is a fingerprint, not a secret

By default `masked` replaces a header value with `length:sha256-prefix64` — stable, so a masked token
can still be correlated across events, across the two twins, and across the Limesium server line (same
scheme), and a 64-bit cryptographic prefix makes accidental collisions negligible. It is **unsalted and
unkeyed**: it prevents plaintext exposure, not offline guessing. A reader with a candidate list
(usernames, tenant names, short API keys) can confirm a candidate by hashing it. Do not treat the
default as a security boundary for guessable values; omit such headers from the selection instead — or
**key** it: `adapter-logging.masking-key` turns the fingerprint into an HMAC-SHA256 under the key, same
shape and stability, guess-proof without the key (a secret — supply it as one). For any other shape the
masker is the `HeaderValueMasker` bean ([§2.8](#28-injectable-collaborators)): a host pins its own (a
fixed `***` for no correlation at all) once, and both twins mask with it. The contract a replacement
must keep: never return the plaintext.

### 6.11 Shared code: legatium-common, inlined by Shade

The byte-identical part of the twins' shared layer lives in the `legatium-common` module
([ADR-0003](../../docs/adr/ADR-0003-legatium-common-inlined-by-shade.md)): the `Traceparent` parser (with
its tests and fuzz target), `HeaderLogProperties` (selection and masking fingerprint, with unit test and
fuzz target), the `ClientLogField` enum with its builder extensions and the `ClientLoggingProperties`
binding (ADR-0003 amendments), `Timeouts`,
`NanoTimeSource`, `CorrelationIdGenerator`, `reportQuietly`/`failOpen`, the MDC keys and scope, and
`BodyReadState`/`decodeTruncated`. The Maven Shade plugin inlines those classes into THIS jar at package
time, the dependency-reduced POM drops the dependency, and `legatium-common` is never
published — consumers keep adding exactly one artifact, and the shared classes stay `internal`
(`-Xfriend-paths`; build from the reactor root or with `-am`).

Everything whose twin copies genuinely differ stays deliberately duplicated: the metrics (per-stack
outcome vocabulary and meter descriptions), the emitters and exchanges, interceptor vs. filter, and
`BoundedBodyCapture` (two different concurrency designs). For those the accepted cost is
unchanged: a change is a conscious port in both directions, and the lockstep tests catch *named* contract
drift (keys, field names, meter names, message text), not behavioural drift inside near-identical code.

---

## 7. Appendix

### 7.1 File map

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
    │   ├── CapturingClientHttpResponse.kt         response wrapper: body tee, read-failure report, close = emission
    │   ├── Exchange.kt                            per-exchange state and the exactly-once guards
    │   ├── ExchangeLogEmitter.kt                  arrival line and completion event
    │   ├── ClientLoggingMetrics.kt                the six meters
    │   └── BoundedBodyCapture.kt                  bounded capture target, read state
    │   (ClientLoggingProperties, ClientLogFields, Traceparent, Timeouts, Mdc, NanoTimeSource,
    │    CorrelationIdGenerator, HeaderLogProperties, BodyCapture helpers and the fail-open guards
    │    live in ../legatium-common - inlined, §6.11)
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
| Lockstep/contract tests (`TwinContractTest`, `UriTemplateAttributeTest`) | pin the twin contracts and the mirrored `RestClient` attribute; the field/template and configuration/reference lockstep (`ClientLogFieldTest`, `ClientLoggingReferenceConfigTest`, `ClientLoggingPropertiesTest`) lives once in legatium-common |

Fuzzing of the shared `Traceparent` parser and header masking lives in legatium-common; the bounded
capture's fuzz target lives here.

### 7.2 Related documents

- [`README.md`](../README.md) — module summary, field family, property table, meters.
- [`legatium-webclient-logging/README.md`](../../legatium-webclient-logging/README.md) — the twin's
  documentation; everything not listed in [§6.1](#61-differences-to-the-webclient-twin) applies there
  unchanged.
- [`/docs/adapter-logging-reference.yml`](../../docs/adapter-logging-reference.yml) — the complete commented
  configuration reference, bound by both twins.
- [`/docs/elk/README.md`](../../docs/elk/README.md) — the Elasticsearch component template for the
  `adapter_*` fields.
- [`/docs/adr/`](../../docs/adr/) — the decision records: fuzzing signal, trace identity, shared core,
  id generator, headers masked by default, bodies logged by outcome, the `adapter` vocabulary.
- [Limesium](https://github.com/Inqudium/limesium) — the inbound sibling: same design, `endpoint_*`
  fields, `endpoint-logging.*` namespace.
