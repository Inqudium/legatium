# legatium-webclient-logging — Guide

One structured `client_*` log line per outbound HTTP exchange made through Spring's `WebClient` — with the
same message format, the same field family, the same `client-logging.*` configuration and the same meters
as the RestClient twin [`legatium-restclient-logging`](../../legatium-restclient-logging/README.md). The
inbound counterpart of the whole family is the sibling project
[Limesium](https://github.com/Inqudium/limesium).

This guide is the long-form companion to the module [README](../README.md). It explains what the module
does, how it is built, how to drop it into a foreign application, what can be configured, what it
measures, and which behaviours are specific to the reactive client stack. Everything here is derived from
the code under `src/main/kotlin/eu/inqudium/legatium/webclient/logging/`; when the two disagree, the code
wins.

## Table of contents

1. [Introduction](#1-introduction)
   1. [What the module does](#11-what-the-module-does)
   2. [What the module deliberately does not do](#12-what-the-module-deliberately-does-not-do)
   3. [The exchange line](#13-the-exchange-line)
   4. [Relation to the RestClient twin](#14-relation-to-the-restclient-twin)
2. [Architecture](#2-architecture)
   1. [Component overview](#21-component-overview)
   2. [Auto-configuration and registration](#22-auto-configuration-and-registration)
   3. [Lifecycle of one exchange](#23-lifecycle-of-one-exchange)
   4. [Emission point: the body's terminal signal](#24-emission-point-the-bodys-terminal-signal)
   5. [The body tees](#25-the-body-tees)
   6. [MDC and the reactive call](#26-mdc-and-the-reactive-call)
   7. [Fail-open contract](#27-fail-open-contract)
   8. [Injectable collaborators](#28-injectable-collaborators)
3. [Using it in a foreign project](#3-using-it-in-a-foreign-project)
   1. [Prerequisites](#31-prerequisites)
   2. [Adding the dependency](#32-adding-the-dependency)
   3. [Filter order and other filters](#33-filter-order-and-other-filters)
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
   1. [Differences to the RestClient twin](#61-differences-to-the-restclient-twin)
   2. [Cancellation and the missing status](#62-cancellation-and-the-missing-status)
   3. [Timeouts: connector vs. operator](#63-timeouts-connector-vs-operator)
   4. [A body nobody consumes](#64-a-body-nobody-consumes)
   5. [Late body chunks after cancellation](#65-late-body-chunks-after-cancellation)
   6. [The request body inserter is wrapped](#66-the-request-body-inserter-is-wrapped)
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

`legatium-webclient-logging` is a Spring Boot auto-configured `ExchangeFilterFunction`, attached through
Boot's `WebClientCustomizer` to every `WebClient` the host builds through Boot. For every outbound HTTP
exchange it:

- resolves the exchange identity per ADR-0002: a conformant `traceparent` on the outgoing request — put
  there by the host's tracing propagation — makes its trace id **the** request id and leaves the wire
  untouched; only a traceless call adopts a correlation header already on the request, or generates one
  and **sends** it, so the peer can quote it;
- optionally logs an **arrival line** the moment the request is sent;
- measures the exchange duration with an injectable monotonic time source — until the response body's
  terminal signal;
- optionally tees the request body as the caller's inserter writes it and the response body as the
  application reads it (bounded, never buffered or replayed, frozen at emission);
- optionally records the selected request/response headers, with stable masking of sensitive values;
- parses the outgoing W3C `traceparent` header (`traceId`/`spanId`) so the event stays joinable with its
  trace;
- emits **exactly one** structured completion event at the **response body's terminal signal** — after
  the application (or the client's own `retrieve` plumbing) consumed or released the body, so status,
  headers, body and duration are final; a call without a response emits at the response `Mono`'s own
  error or cancel signal;
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
  capture limit; the logger level is the only volume control ([§4.5](#45-logger-levels)).
- **No exporting of a `MeterRegistry`.** The host's registry is consumed if present; otherwise a private
  `SimpleMeterRegistry` absorbs the values.
- **No call-wide thread-local MDC.** A reactive call hops event-loop threads; the identity rides the
  emission scope and the message ([§2.6](#26-mdc-and-the-reactive-call)).
- **No clients built by hand.** The customizer covers every client built through Boot's builder (and the
  HTTP service client groups built from it); a hand-built `WebClient` gets the filter bean added by the
  host ([§3.4](#34-overriding-beans)).

### 1.3 The exchange line

On the logger `http-client-exchange` (configurable) a completed exchange looks like this in a plain-text
appender:

```
Client http exchange POST https://api.example.com/things/42 -> 200 [client_request_id=4bf92f3577b34da6a3ce929d0e0e4736 traceId=4bf92f3577b34da6a3ce929d0e0e4736 spanId=00f067aa0ba902b7]
```

The trace suffix appears only when the outgoing request carried a conformant W3C `traceparent` header —
its trace id then doubles as the request id (ADR-0002). Alongside the message, the event carries SLF4J
key-values that a structured encoder turns into fields:

```json
{
  "message": "Client http exchange POST https://api.example.com/things/42 -> 200 [client_request_id=4bf92f3577b34da6a3ce929d0e0e4736 traceId=4bf92f3577b34da6a3ce929d0e0e4736 spanId=00f067aa0ba902b7]",
  "level": "INFO",
  "logger": "http-client-exchange",
  "client_outcome": "success",
  "client_duration_ms": 17,
  "client_request_method": "POST",
  "client_response_status_code": 200,
  "client_url_host": "api.example.com",
  "client_url_path": "/things/42",
  "client_url_template": "https://api.example.com/things/{id}",
  "client_request_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "client_method": "POST",
  "client_route": "https://api.example.com/things/42",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "00f067aa0ba902b7"
}
```

The `client_request_id` / `client_method` / `client_route` / `traceId` / `spanId` entries come from the
MDC ([§5.2](#52-mdc-keys)); the `client_*` key-values are the field family of [§5.1](#51-log-fields). How
MDC entries land in the document (flat, nested, renamed) is the encoder's decision.

With the optional arrival line enabled, a second, earlier line precedes it:

```
Client http exchange started POST https://api.example.com/things/42 [client_request_id=4bf92f…]
```

The arrival line carries no outcome, status or duration, so a dashboard keyed on `client_outcome` still
sees exactly one event per call.

### 1.4 Relation to the RestClient twin

The module is the **WebClient twin** of `legatium-restclient-logging`. The RestClient module is the
reference implementation and owns the cross-stack contract:

| Contract | Owner | Lockstep test in this module |
|---|---|---|
| Configuration keys and defaults | [`/docs/client-logging-reference.yml`](../../docs/client-logging-reference.yml) | `ClientLoggingReferenceConfigTest` in `legatium-common` (one `ClientLoggingProperties` class for both twins, bound against the YAML once) |
| Field family and index mapping | [`/docs/elk/…component-template.json`](../../docs/elk/README.md) | `ClientLogFieldTest` in `legatium-common` (one enum for both twins, locked against the template once) |
| Message text and meter names | the RestClient module's emitter and metrics | `TwinContractTest` |

`legatium-common` pulls both files from the repository-shared `/docs` as **test resources** (declared
in its `pom.xml`), so a missing checkout fails at resource processing with a clear message rather than
as a silent contract drift. The consequence for a consumer: a dashboard, alert or index mapping written
for one client works unchanged for the other — and an application may carry both modules.

---

## 2. Architecture

### 2.1 Component overview

Six Kotlin files in one package, `eu.inqudium.legatium.webclient.logging`, plus the shared layer, in
five layers:

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Auto-configuration                                                           │
│   ClientLoggingAutoConfiguration                                             │
│     └─ WebClientCustomization (WebClientCustomizer, late)                    │
│   ClientLoggingProperties · HeaderLogProperties (both shared)                │
├──────────────────────────────────────────────────────────────────────────────┤
│ Client lifecycle                                                             │
│   ClientRequestLoggingFilter (ExchangeFilterFunction)                        │
│     • response Mono: map → onResponse, doOnError, doOnCancel, doFinally      │
│     • response body (mutated ClientResponse): tee, doOnComplete/Error/Cancel,│
│       doFinally → complete  ◀ emission                                       │
├──────────────────────────────────────────────────────────────────────────────┤
│ State and emission                                                           │
│   Exchange / ExchangeState                                                   │
│   ExchangeLogEmitter  ──▶  ClientLogField (shared)                           │
│   ClientLoggingMetrics                                                       │
├──────────────────────────────────────────────────────────────────────────────┤
│ Capture                                                                      │
│   CapturingClientHttpRequestDecorator · tee() · BoundedBodyCapture           │
├──────────────────────────────────────────────────────────────────────────────┤
│ Cross-cutting (legatium-common, inlined)                                     │
│   ClientLogField · MdcKeys · TraceMdcKeys · MdcScope · Traceparent · Timeouts│
│   NanoTimeSource · CorrelationIdGenerator · reportQuietly · failOpen         │
└──────────────────────────────────────────────────────────────────────────────┘
```

| Class | Responsibility |
|---|---|
| `ClientLoggingAutoConfiguration` | Registers the filter bean, the default `NanoTimeSource` / `CorrelationIdGenerator`, and — when Boot's `spring-boot-webclient` is present — a late `WebClientCustomizer` that appends the filter. |
| `ClientLoggingProperties` | The `client-logging.*` binding, validated in `init` - shared (legatium-common - §6.11), the very class the RestClient twin binds. `HeaderLogProperties` (shared too) is one header section. |
| `ClientRequestLoggingFilter` | Everything that decides **what** is logged and counted: activation by host and path, fail-open wiring (identity, the rebuilt request with correlation header and body tee), the arrival line, the signal mapping of the response `Mono`, the response mutation with the body hooks, the exactly-once `complete`. |
| `Exchange` / `ExchangeState` | Per-exchange state between entry and emission; one atomic `OPEN → RESPONDED → COMPLETED` state instead of loose flags. |
| `ExchangeLogEmitter` | Builds and emits the arrival line and the completion event; freezes the captures first; resolves level and outcome (timeouts via the shared `Timeouts`, `cancelled` on top); records body sizes; opens the emission `MdcScope` with trace ownership. |
| `ClientLogField` | The wire names and the exact JVM type of each structured field; a wrongly typed value drops the field with a warning, never the event. Shared (legatium-common): one enum for both twins. |
| `ClientLoggingMetrics` | The six meters - the fixed-tag meters pre-registered (four outcomes here), the body meters created lazily per tag - with per-meter fallback to a private registry on registration conflict. |
| `CapturingClientHttpRequestDecorator` / `tee` | The `DataBuffer` map-tee: wraps the connector's request while the inserter writes; the same `tee` transforms the response body flux. |
| `BoundedBodyCapture` | The lock-guarded, freezable capture target; count-only mode with limit `0`; the response-side read state (`BodyReadState`). |
| `MdcScope` | Puts identity and trace keys into the MDC for the duration of one emission and restores the previous values. |
| `Traceparent` / `Timeouts` | Strict W3C `traceparent` parsing to `(traceId, spanId)`; the cause-chain walk that classifies a failure as a timeout — recognising Reactor Netty's timeout by name. |
| `NanoTimeSource` / `CorrelationIdGenerator` | Injectable time and id; `SYSTEM` and `DEFAULT` are the production defaults. |
| `reportQuietly` / `failOpen` | Guard the diagnostics channel (counter + internal log) of every catch block. |

### 2.2 Auto-configuration and registration

`ClientLoggingAutoConfiguration` is listed in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` and is conditional on
`client-logging.enabled` (default `true`) only — no web application type. It registers:

| Bean | Condition | Purpose |
|---|---|---|
| `NanoTimeSource` | `@ConditionalOnMissingBean` | `NanoTimeSource.SYSTEM` |
| `CorrelationIdGenerator` | `@ConditionalOnMissingBean` | `CorrelationIdGenerator.DEFAULT` (counting generator — ADR-0004) |
| `ClientRequestLoggingFilter` | `@ConditionalOnMissingBean` | the filter, built from the bound properties and the host's `MeterRegistry` (`ObjectProvider`; private `SimpleMeterRegistry` without one) |
| `WebClientCustomizer` | `@ConditionalOnClass(WebClientCustomizer)`, `@Order(LOWEST_PRECEDENCE - 10)` | `builder.filter(filter)` on every `WebClient.Builder` Boot hands out |

Because the filter is its own bean, a host can replace it while keeping the customizer
([§3.4](#34-overriding-beans)). Boot's `spring-boot-webclient` module is an **optional** dependency:
without it the filter bean still exists and the host attaches it by hand. The same property namespace and
the same bean names as the RestClient twin — the two auto-configurations never clash, and both may be
active in one application.

### 2.3 Lifecycle of one exchange

```
WebClient.retrieve()/exchangeToMono()/exchange()
   │  (client observation opened; traceparent injected into the request builder BEFORE build())
   ▼
[earlier filters] ──▶ ClientRequestLoggingFilter.filter(request, next)
                       │
                       ├─ shouldNotFilter(url)?  ──yes──▶ next.exchange(request)   (untouched pass-through)
                       │
                       ├─ wireOrNull(request)    ──null─▶ next.exchange(request)   (fail-open, stage=wiring)
                       │     • request id: traceparent trace id, else header on the request, else generated
                       │       and ADDED (the request is rebuilt: ClientRequest is immutable)
                       │     • body captures created if logging OR measuring is on; the request body
                       │       inserter wrapped with the tee decorator
                       │     • request headers selected and masked from the OUTGOING request
                       │     • traceId/spanId parsed; startNanos read; gauge exchanges.open += 1
                       │
                       ├─ logRequestStartIfEnabled
                       │
                       └─ Mono.defer { next.exchange(outgoing) }
                            .map        { response -> onResponse(exchange, response) }   ← state RESPONDED
                            .doOnError  { exchange.failure = it }
                            .doOnCancel { exchange.cancelled = true }
                            .doFinally  { signal -> complete unless a response was delivered }

 onResponse: response.mutate().body { flux ->
                 Flux.defer { capture.markStarted(); flux }
                     .map { tee(capture, it) }
                     .doOnComplete { capture.markCompleted() }
                     .doOnError    { exchange.failure = it }
                     .doOnCancel   { exchange.cancelled = true }
                     .doFinally    { complete(exchange) }          ← emission
             }.build()
```

`complete` is the **exactly-once** gate: a `getAndSet(COMPLETED)` on `Exchange.state` decides which
signal wins; the winner decrements the gauge and calls `ExchangeLogEmitter.logExchange`. The emitter
freezes the body captures, computes duration, status, outcome and level, records body sizes, gates on the
logger level, opens the `MdcScope`, and writes one event.

### 2.4 Emission point: the body's terminal signal

`WebClient` hands the caller a `ClientResponse` whose body is a `Flux<DataBuffer>` the caller — or the
client's own `retrieve()`/`exchangeToMono()` plumbing — subscribes to afterwards. That is when the bytes
flow, and when the exchange is truly over. Emitting when the response `Mono` completes would log a body
of zero bytes, a duration that excludes the read, and — for the client's own error handling, which reads
the body of a 4xx/5xx to build its `WebClientResponseException` — an exchange that is not over. The
filter therefore mutates the delivered response so that its body carries the tee and the terminal hooks:

| Signal | Where | Emission |
|---|---|---|
| response `Mono` errors (connection refused, a connector timeout) | `doFinally(ERROR)` on the response Mono | immediately; `-> -`, no status |
| response `Mono` is cancelled before a response (a downstream `timeout()`, a disposed caller) | `doFinally(CANCEL)` on the response Mono | immediately; `cancelled`, `-> -` |
| response delivered, body completes | `doFinally` on the body flux | at completion — status, headers, body and duration final |
| response delivered, body errors (reset mid-stream) | `doFinally` on the body flux | at the error — `failure` with the received status |
| response delivered, body subscription cancelled (`take`, a timeout after the status line) | `doFinally` on the body flux | at the cancel — `cancelled` with the received status |
| response delivered, body never subscribed nor released | — | never: the exchange stays open on the gauge ([§6.4](#64-a-body-nobody-consumes)) |

Every path of `WebClient` that hands the response to application code subscribes or releases the body:
`retrieve().bodyToMono/Flux/toEntity` subscribe, `toBodilessEntity` releases, `exchangeToMono` and
`exchangeToFlux` release whatever the handler did not consume. Only a raw `exchange()` (deprecated) leaves
that duty to the caller.

### 2.5 The body tees

Bodies are never pre-read, buffered or replayed. The module installs **passive map-tees**:

- The **request body**: `ClientRequest` is immutable and carries its body as a `BodyInserter`. The filter
  rebuilds the request with a wrapping inserter that hands the connector's `ClientHttpRequest` to the
  original inserter **decorated** — `CapturingClientHttpRequestDecorator` tees `writeWith` /
  `writeAndFlushWith`, the one place every body encoder passes. A `Mono` body stays a `Mono` (the
  connector's single-buffer path is preserved); a bodiless request (`setComplete` only) leaves the
  capture at zero bytes and the field absent.
- The **response body**: `response.mutate().body(transformer)` transforms the body flux lazily — nothing
  is read until the application subscribes. The transformer marks the subscription, tees every buffer,
  marks completion, and completes the exchange at the terminal signal.
- `tee` reads at most `capture.remainingCapacity()` bytes out of each `DataBuffer` with a
  **non-advancing** read (the read position is untouched), counts the full length, and returns the
  original buffer. Ownership, pooling and release are exactly those of an undecorated exchange.

`BoundedBodyCapture` is the target: a `ByteArrayOutputStream` of at most `max-body-bytes`, a total byte
counter, and a `frozen` flag — all under one uncontended `ReentrantLock`. With limit `0` it runs in
**count-only** mode for the body-size meters: nothing is buffered, every byte is counted, `tee` copies
nothing.

The captures exist only when a body is logged **or** measured; without either, the request goes to the
connector as the caller built it (unless a correlation header had to be added), and the response body is
mutated for the terminal hooks only.

**The capture mirrors consumption, not transmission.** The log shows exactly the bytes the application
read — no more. A response body the application never subscribes to is logged as absent; a body consumed
partially (`take`, a cancelled subscription) is captured to exactly that extent, and the `[truncated, N
bytes total]` note counts what flowed, not `Content-Length`. Because of that, the log cannot tell a body
the peer sent but the application dropped from one that was never sent; the counter
`client.response.body.read` ([§5.4](#54-meters)) exists for exactly that distinction — where a
`releaseBody()` (which subscribes and drains) counts as `complete`, and only a body nobody ever
subscribed to would be `unread` (and, never completing, is not counted at all — the gauge shows it).

### 2.6 MDC and the reactive call

There is no call-wide thread-local MDC in a reactive client: the thread that runs the filter is not the
thread that receives the response, and neither is the one that reads the body. The module provides the
`client_*` identity in two places:

| Place | Mechanism | Who sees it |
|---|---|---|
| Emission scope | `MdcScope` around the single `log()` call, trace keys owned | structured encoders emitting MDC fields on the exchange line and the arrival line |
| Message | inline `[client_request_id=…]` | plain-text appenders |

The emission scope is an **additive overlay**: whatever MDC the completing thread carries — with
context propagation configured, the inbound request's `endpoint_*` keys (Limesium) restored around the
operator — stays visible beside the client identity; only the trace keys are owned (a parsed id is
installed, an unparsed one removed for the scope, so a stale bridge id on an event-loop thread never
joins the event to a foreign trace). Propagating the client identity *into* the caller's reactive
operators is deliberately not attempted: the caller's own context (its inbound request, its trace) is the
identity that matters there, and the host's context-propagation setup owns it.

### 2.7 Fail-open contract

A logging component must never fail the call it describes. The module enforces that at every boundary
where it calls host-provided code (MDC adapter, appenders, `MeterRegistry`, the client's request and
response objects):

| Stage | Where | What happens on failure | Counted as |
|---|---|---|---|
| wiring | `wireOrNull` (correlation bean, header selection, request rebuild, capture construction) | the filter degrades to a plain pass-through for this call | `failopen{stage=wiring}` |
| wiring | gauge bookkeeping in `complete` | the event still follows | `failopen{stage=wiring}` |
| wiring | body-size recording, operational counter updates | the event follows without the sample / the count | `failopen{stage=wiring}` |
| arrival | `logRequestStart` (including the level gate) | the arrival line is dropped | `failopen{stage=arrival}` |
| emission | `logExchange` — everything after the exactly-once CAS | the exchange event is **lost**; the body signal propagates normally | `failopen{stage=emission}` |
| registration | `ClientLoggingMetrics.registerOrFallback` | the conflicting meter lives in a private registry, warned once per name | — |

A downstream filter that **throws** while assembling its publisher (instead of returning `Mono.error`) is
turned into the exchange's error signal by the `Mono.defer` around the exchange call, so the callbacks run
and the gauge does not leak. Every catch block reports through `reportQuietly`, which swallows a failure
of the diagnostics channel itself. `InterruptedException` is caught separately and the interrupt flag is
restored.

Failures of the logging are reported on the module's **own** loggers
(`eu.inqudium.legatium.webclient.logging.ClientRequestLoggingFilter`, `…ExchangeLogEmitter`,
`…ClientLoggingMetrics`), never on the exchange logger, so the exchange stream stays parseable.

**Security note.** Fail-open is the inverse of what an audit log needs: a host-side fault silently
removes the call from the log instead of failing it. The exchange log is therefore an **observability**
feature with no completeness guarantee; a regulatory audit trail of outbound calls must come from a
fail-closed component. The compensating controls are `client.logging.failopen` and the
`exchanges.open` gauge ([§5.5](#55-reading-the-meters-together)) — alert on them.

### 2.8 Injectable collaborators

Time and randomness are injected, not ambient:

- `NanoTimeSource` — monotonic nanoseconds for `client_duration_ms` and the slow threshold; the single
  production read of `System.nanoTime()` is `NanoTimeSource.SYSTEM`.
- `CorrelationIdGenerator` — the id for traceless calls without a correlation header; `DEFAULT` (the
  counting generator, ADR-0004) by default: one atomic increment per call, no `SecureRandom` on the
  event loop. Never consulted for a traced call, and in a host with tracing configured never at all
  ([§6.8](#68-tracing-makes-every-call-traced)).

Both are `fun interface`s, both are `@ConditionalOnMissingBean` beans, and both are what the module's
tests drive from an `AtomicLong` / a fixed string without any mocking library.

---

## 3. Using it in a foreign project

### 3.1 Prerequisites

| Requirement | Notes |
|---|---|
| Spring Boot 4.x application using `WebClient` | any application type — servlet, reactive, or none; `WebClient` itself comes with `spring-webflux` |
| Boot's `spring-boot-webclient` (via `spring-boot-starter-webclient`, `spring-boot-starter-webflux`, or transitively) | provides `WebClient.Builder` and the `WebClientCustomizer` contract the auto-configuration hooks; **optional** for the module — without it the filter bean is attached by hand |
| Java 21, Kotlin stdlib | the module is written in Kotlin; a Java host only needs `kotlin-stdlib`, which the jar pulls transitively |
| SLF4J 2.x binding (Logback by default in Boot) | the module uses the fluent `LoggingEventBuilder` API (`addKeyValue`) |
| Micrometer core | present via any Boot starter; an actuator `MeterRegistry` is optional |
| A connector | whatever the host's `WebClient` uses (Reactor Netty by default, the JDK `HttpClient`, Jetty, ...) — the module is connector-agnostic |

The module is a **library**, not a starter: it declares `spring-boot-autoconfigure`, `slf4j-api`,
`spring-webflux`, `reactor-core`, `micrometer-core`, `kotlin-stdlib` and the optional
`spring-boot-webclient` — no logging backend, no YAML, no connector are forced onto the host.

### 3.2 Adding the dependency

```xml
<dependency>
    <groupId>eu.inqudium</groupId>
    <artifactId>legatium-webclient-logging</artifactId>
    <version><!-- current release: see the badge below --></version>
</dependency>
```

The current release is shown live by the Maven Central badge:
[![Maven Central](https://img.shields.io/maven-central/v/eu.inqudium/legatium-webclient-logging.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/eu.inqudium/legatium-webclient-logging)

That is all: the auto-configuration registers the filter and the customizer, every call through a
Boot-built `WebClient` is logged on the `http-client-exchange` logger at INFO, the request id comes from
the `traceparent` trace id (traceless calls send an `X-Correlation-Id` instead — ADR-0002), and the six
meters are registered in the host's `MeterRegistry` if one exists.

To remove the module again without touching the classpath:

```yaml
client-logging:
  enabled: false
```

### 3.3 Filter order and other filters

The customizer is ordered at `Ordered.LOWEST_PRECEDENCE - 10`, so the filter is appended **behind** the
filters of earlier customizers and of the builder's own configuration, and runs **inside** them —
closest to the connector:

- an authentication filter outside it has already added its header, so the logged (and masked) request
  headers are what the peer receives;
- a retrying filter outside it (or a `retryWhen` around the call) invokes it once per attempt — one line
  per attempt ([§6.7](#67-retries-yield-one-line-per-attempt));
- filters a host adds **after** the customizers ran (directly on a builder it obtained from Boot) run
  inside this one and are outside that guarantee.

The `traceparent` header is not affected by the order at all: the client observation Boot registers
injects it into the request builder **before** the request is built and the filter chain runs
([§5.6](#56-trace-correlation)).

Activation is evaluated **in the filter** (`shouldNotFilter`), so its semantics are byte-identical with
the RestClient twin.

### 3.4 Overriding beans

Every default is `@ConditionalOnMissingBean`:

```kotlin
@Configuration(proxyBeanMethods = false)
class ClientLoggingCustomisation {

    /** Deterministic ids in a test profile, or a different id format (a peer that insists on UUIDs). */
    @Bean
    fun correlationIdGenerator(): CorrelationIdGenerator =
        CorrelationIdGenerator { UUID.randomUUID().toString() }

    /** Only if the host owns a monotonic clock abstraction already. */
    @Bean
    fun nanoTimeSource(clock: MonotonicClock): NanoTimeSource =
        NanoTimeSource { clock.nanos() }
}
```

A host-defined `ClientRequestLoggingFilter` bean replaces the **filter**, not the wiring: the
auto-configured customizer still attaches it to every Boot-built client. The constructor takes
`(ClientLoggingProperties, NanoTimeSource, CorrelationIdGenerator, MeterRegistry)`:

```kotlin
@Bean
fun clientRequestLoggingFilter(
    properties: ClientLoggingProperties,
    nanoTime: NanoTimeSource,
    ids: CorrelationIdGenerator,
    registry: MeterRegistry,
): ClientRequestLoggingFilter = ClientRequestLoggingFilter(properties, nanoTime, ids, registry)
```

A client built **by hand** receives the filter from the host:

```kotlin
val client = WebClient.builder().baseUrl(url).filter(filter).build()
```

Keep in mind the one-instance-per-registry rule of the gauge ([§6.9](#69-one-metrics-instance-per-registry)).

### 3.5 Logging backend and structured output

The module emits through SLF4J's fluent API. Every exchange event carries its data in **two places**, and
an encoder treats them differently:

| Data | Carried as | Examples |
|---|---|---|
| The field family | SLF4J **key-value pairs** (`addKeyValue`) | `client_outcome`, `client_duration_ms`, `client_url_host`, `client_response_body` |
| The identity and trace context | **MDC** entries, set by the emission scope | `client_request_id`, `client_method`, `client_route`, `traceId`, `spanId` (from the `traceparent` header) |

A plain `%msg` pattern shows neither — only the message, which repeats the gist inline
(`… -> 200 [client_request_id=…]`) precisely for that case. Logback offers three ways to render the
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
13:54:58.534 INFO  [reactor-http-epoll-2] http-client-exchange - Client http exchange POST https://api.example.com/things/42 -> 200 [client_request_id=4bf9… traceId=4bf9… spanId=00f0…] client_outcome=success client_duration_ms=17 client_request_method=POST client_response_status_code=200 client_url_host=api.example.com client_url_path=/things/42 client_url_template=https://api.example.com/things/{id} [client_method=POST, client_request_id=4bf9…, client_route=https://api.example.com/things/42, traceId=4bf9…, spanId=00f0…]
```

- `%kvp{NONE}` leaves values bare; `%mdc` prints every entry that is present, so the trace keys appear
  only on traced calls.
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

One JSON object per event — but the key-value pairs arrive as a **list of single-key objects** and the
MDC nested under `"mdc":{…}`. Correct and safe, yet awkward to map onto the flat `client_*` fields;
suitable for local JSON inspection, not for an index.

#### Option 3 — Spring Boot structured logging (JSON, flat, typed — recommended for an index)

```yaml
logging:
  structured:
    format:
      console: ecs      # or logstash, gelf
  level:
    http-client-exchange: INFO
    eu.inqudium.legatium.webclient.logging: WARN
```

Key-value pairs and MDC entries become **flat top-level fields**, and values keep their JVM type — which
is what the type assertion in `ClientLogField` guarantees on the producing side. This is the shape the
component template in [§3.6](#36-index-mapping-elk) is written for.

| Option | Output | Key-value pairs | MDC | Typed values | Escapes control chars | Use for |
|---|---|---|---|---|---|---|
| 1 `PatternLayout` `%kvp` `%mdc` | text | inline `k=v` | inline `k=v` | no (all text) | **no** | terminals, local files, tests |
| 2 `JsonEncoder` | JSON | list of objects | nested `mdc` | partly | yes | local JSON inspection |
| 3 `StructuredLogEncoder` | JSON | flat fields | flat fields | **yes** | yes | **log index (ELK etc.)** |

Whatever the option, keep the `eu.inqudium.legatium.webclient.logging` logger at WARN or lower: it
carries the module's own failure reports.

### 3.6 Index mapping (ELK)

The thirteen `client_*` fields have a ready-made Elasticsearch component template in the repository-shared
[`/docs/elk/`](../../docs/elk/README.md). Compose it into the data-stream mapping **before** the first
event arrives — an unmapped body or header field would be mapped dynamically and become searchable, which
the payload fields' `index: false` deliberately prevents. The MDC-carried keys are intentionally not in
that template: where they land depends on the host's encoder layout; map them where the encoder
configuration lives.

### 3.7 Verifying the integration

1. Make any call through a Boot-built `WebClient`:

   ```kotlin
   webClientBuilder.baseUrl("https://httpbin.org").build().get().uri("/get").retrieve().bodyToMono(String::class.java).block()
   ```

   Expect one `http-client-exchange` line with `client_request_id=…`. Without tracing configured, the
   peer received an `X-Correlation-Id` with that id (httpbin echoes request headers in its body). With
   Micrometer Tracing configured, expect `traceId=… spanId=…` on the line and **no** `X-Correlation-Id`
   at the peer (ADR-0002).

2. Point the client at a closed port and confirm the exchange line with `-> -`,
   `client_outcome=failure` at ERROR with the cause attached.

3. Apply `.timeout(Duration.ofMillis(1))` to a call and confirm `client_outcome=cancelled` — then
   configure the connector's response timeout instead and confirm `client_outcome=timeout`
   ([§6.3](#63-timeouts-connector-vs-operator)).

4. Check the meters (with actuator):

   ```bash
   curl -s localhost:8080/actuator/metrics/client.logging.events
   curl -s localhost:8080/actuator/metrics/client.logging.exchanges.open
   ```

   `events` should equal the number of logged lines; `exchanges.open` should be `0` when idle.

---

## 4. Configuration

All properties live under `client-logging.*`. The namespace is **identical** to the RestClient twin's by
construction: both twins bind the one shared `ClientLoggingProperties` class. The complete, commented
reference with every default is the repository-shared
[`/docs/client-logging-reference.yml`](../../docs/client-logging-reference.yml);
`ClientLoggingReferenceConfigTest` in `legatium-common` binds it against that class and fails the build
on any drift.

### 4.1 Property reference

| Property | Type | Default | Meaning |
|---|---|---|---|
| `enabled` | boolean | `true` | Master switch. `false` makes the auto-configuration back off — no filter, no customizer, no beans. A context-start decision, not a runtime toggle. |
| `logger-name` | string | `http-client-exchange` | Logger of the arrival line and the exchange event. Its level is the runtime volume control ([§4.5](#45-logger-levels)). |
| `correlation-id-header` | string (RFC 9110 token) | `X-Correlation-Id` | Header read from a **traceless** request (no conformant `traceparent` — ADR-0002); when absent or blank, an id is generated and ADDED to the request under this name. A traced call takes its request id from the `traceparent` trace id, ignores this header and adds nothing. |
| `include-query-string` | boolean | `true` | Log the query string as its own field `client_url_query` (never part of the path). |
| `log-request-start` | boolean | `false` | Additionally log an arrival line before the exchange, at INFO, under the emission MDC. Carries no outcome/status/duration. |
| `include-path-patterns` | list of `PathPattern` | `[]` | Request paths the filter is active for at all, whatever the host; empty = every call. Parsed once at startup; an invalid pattern fails the context. |
| `exclude-path-prefixes` | list of strings | `[]` | Request-path prefixes the filter skips entirely — no event, no correlation header, no gauge movement. An exclude always wins over an include. |
| `exclude-hosts` | list of strings | `[]` | Peer hosts the filter skips entirely (case-insensitive, without port). |
| `slow-request-threshold` | duration | `5s` | At/above this duration an INFO call escalates to WARN and is flagged `client_slow: true`; the outcome stays `success`. Measured until the body's terminal signal. Must be ≥ 1 ms. |
| `request-headers.includes` / `.excludes` / `.masked` | lists of header names | `[]` | See [§4.2](#42-header-sections). |
| `response-headers.includes` / `.excludes` / `.masked` | lists of header names | `[]` | See [§4.2](#42-header-sections). |
| `log-request-body` | boolean | `false` | Tee the request body into `client_request_body` as the inserter writes it, up to `max-body-bytes`. |
| `log-response-body` | boolean | `false` | Tee the response body into `client_response_body` as the application reads it, up to `max-body-bytes`. |
| `measure-request-body-size` | boolean | `false` | Record `client.request.body.size`; independent of `log-request-body`. |
| `measure-response-body-size` | boolean | `false` | Record `client.response.body.size` and `client.response.body.read`; independent of `log-response-body`. |
| `max-body-bytes` | int > 0 | `16384` | Capture limit per body. Bounds **memory** — and the tee's transient copy per buffer — not the exchange: bytes beyond it still flow; the logged value is truncated with a note of the total size. |

### 4.2 Header sections

Each direction has one section with three lists; matching is case-insensitive throughout.

| List | Semantics |
|---|---|
| `includes` | Names to log. **Empty logs nothing** (the safe default). The entry `*` logs every header the message carries, deduplicated case-insensitively. |
| `excludes` | Names removed from the included set — meaningful mainly with `*`. An exclude always wins. `*` is rejected here at binding time. |
| `masked` | Names whose **value** is replaced by a fingerprint `length:hex` — the character length plus the first 64 bits of the SHA-256 of the UTF-8 value. `*` masks every logged header. Masking affects only headers that are logged. |

Multi-valued headers are joined with `, `. The selected pairs are rendered into one display-only field
per direction as `[Name:"value", Name2:"value2"]`; nothing is emitted when the selection is empty or no
selected header is present.

Request headers are selected at **wiring time** from the **outgoing** request — after the correlation
header was added — so a selected `X-Correlation-Id` shows what actually went out; response headers at
**emission**, off the delivered response.

### 4.3 Body logging and body measuring

Four independent flags, two per direction:

| `log-*-body` | `measure-*-body-size` | Capture installed | Buffered | Effect |
|---|---|---|---|---|
| off | off | no | — | request untouched (unless a correlation header is added); response body mutated for the terminal hooks only |
| on | off | yes, limit `max-body-bytes` | up to the limit | field logged; no size sample |
| off | on | yes, limit `0` (count-only) | nothing | size sample recorded; no field; `tee` copies nothing |
| on | on | yes, limit `max-body-bytes` | up to the limit | both |

Rules that hold for every combination:

- The tees are passive: bytes are counted and (up to the limit) copied as they flow; nothing is pre-read,
  replayed or withheld. Backpressure and streaming behaviour are untouched.
- An **unread response body** is logged as absent; no size sample is recorded — and the exchange does not
  complete until something consumes or releases the body ([§6.4](#64-a-body-nobody-consumes)).
- Zero-byte bodies produce no field and no sample.
- Truncation is **byte-bounded**, and the decoder leaves an incomplete trailing multi-byte sequence
  undecoded rather than rendering a replacement character: `…<prefix>... [truncated, 12345 bytes total]`.
- The log charset is the one the `Content-Type` declares, UTF-8 when absent or unparsable.
- `measure-*` records what actually flowed, **exact beyond** `max-body-bytes`.
- The captures are **frozen at emission**: a body chunk still in flight after a cancellation can no
  longer change what was logged ([§6.5](#65-late-body-chunks-after-cancellation)).

### 4.4 Activation: hosts and paths

```
active(url) = url.host not in exclude-hosts
              AND (include-path-patterns is empty  OR  any pattern matches url.path)
              AND no exclude-path-prefix is a prefix of url.path
```

An inactive call passes through **without any trace**: the connector receives the caller's very request
object; no correlation header, no event, no gauge movement, no counters. The semantics — `PathPattern`
against the request path whatever the host, prefix exclusion against the decoded path, case-insensitive
host exclusion — are byte-identical with the RestClient twin's ([§4.4 there](../../legatium-restclient-logging/docs/GUIDE.md#44-activation-hosts-and-paths)).

### 4.5 Logger levels

Severity and semantic are decoupled: the level only decides how loud — and whether — a line is emitted;
`client_outcome` carries the disposition ([§5.3](#53-levels-and-outcomes)):

| `http-client-exchange` level | Emitted |
|---|---|
| `INFO` | every call |
| `WARN` | failures (5xx), timeouts, cancellations, slow calls — and errored calls |
| `ERROR` | only calls whose exchange or body errored |
| `OFF` | nothing — and no event is even assembled |

Level and outcome are resolved **before** the event is built, so a disabled level costs no assembly, no
header selection, no body decoding. Metrics are recorded **before** the level gate and are unaffected by
it — except `client.logging.events`, which by definition counts emitted events only.

### 4.6 Validation at startup

`ClientLoggingProperties.init` and `HeaderLogProperties.init` reject, with a message naming the property:

- blank `logger-name` or `correlation-id-header`;
- a `correlation-id-header` that is not an RFC 9110 token (it is written onto every traceless request; a
  connector that validates field names would reject a non-token per call — failing the CALL);
- `max-body-bytes` ≤ 0;
- `slow-request-threshold` < 1 ms;
- blank entries in any list (`exclude-hosts` included);
- `*` in an `excludes` list;
- an unparsable `include-path-patterns` entry (parsed once at filter construction).

### 4.7 Example configurations

**Minimal production profile** — everything logged, telemetry peers excluded, slow threshold tightened:

```yaml
client-logging:
  exclude-hosts:
    - pushgateway.monitoring.svc
  slow-request-threshold: 2s
logging:
  level:
    http-client-exchange: INFO
    eu.inqudium.legatium.webclient.logging: WARN
```

**Diagnostics profile** — headers with masked credentials, both bodies, arrival lines:

```yaml
client-logging:
  log-request-start: true
  log-request-body: true
  log-response-body: true
  request-headers:
    includes: ["*"]
    excludes: [Cookie]
    masked: [Authorization, X-Api-Key]
  response-headers:
    includes: [Content-Type, Content-Length, Retry-After]
```

**Metrics without log volume** — body sizes and consumption measured, only failures logged:

```yaml
client-logging:
  measure-request-body-size: true
  measure-response-body-size: true
logging:
  level:
    http-client-exchange: WARN
```

---

## 5. Metrics and observation

### 5.1 Log fields

The structured fields of the completion event (the arrival line carries method, host, path, template,
query and request headers without outcome/duration/status). The index types are those of the shared
component template; `ClientLogFieldTest` in `legatium-common` keeps the shared enum in lockstep with it.

| Field | Type | Index | doc_values | When present | Notes |
|---|---|---|---|---|---|
| `client_outcome` | keyword | yes | on | always | `success` / `failure` / `timeout` / `cancelled` — the field dashboards split by |
| `client_duration_ms` | long | yes | on | always | from the injected monotonic source; until the body's terminal signal |
| `client_request_method` | keyword | yes | on | always | |
| `client_response_status_code` | short | yes | on | when a response arrived | absent for an errored or cancelled exchange without a response (`-> -`) |
| `client_url_host` | keyword | yes | on | when the URL has a host | `host` or `host:port` |
| `client_url_template` | keyword | yes | on | when `WebClient` recorded a template | e.g. `https://api.example.com/things/{id}` |
| `client_url_path` | keyword | yes | **off** | always | the **raw** path as sent — filter exactly, never group |
| `client_url_query` | keyword | yes | **off** | when the request had one and `include-query-string` is on | raw |
| `client_slow` | boolean | yes | on | only when the threshold was reached | absence means fast |
| `client_request_headers` | keyword | **no** | off | when selected headers are present | display only |
| `client_response_headers` | keyword | **no** | off | when selected headers are present | display only |
| `client_request_body` | keyword | **no** | off | when `log-request-body` is on and bytes were written | display only, bounded |
| `client_response_body` | keyword | **no** | off | when `log-response-body` is on and bytes were read | display only, bounded |

Each field asserts the exact JVM type of its value (`ClientLogField.format`): a wrongly typed value
drops **that field** with a warning, never the event. The throwable of an errored exchange is attached
to the event as its cause.

### 5.2 MDC keys

Set by `MdcScope` around each emission ([§2.6](#26-mdc-and-the-reactive-call)):

| Key | Value | Scope |
|---|---|---|
| `client_request_id` | the request id: the `traceparent` trace id, or the accepted/generated correlation id (ADR-0002) — always set | emission |
| `client_method` | the HTTP method | emission |
| `client_route` | the request **target**: `scheme://host[:port]/path`, query excluded | emission |
| `traceId` | trace id from `traceparent` | emission (owned) |
| `spanId` | parent-id from `traceparent` — the **local client span** the peer will see as its parent | emission (owned) |

### 5.3 Levels and outcomes

Resolved in this order in `ExchangeLogEmitter`:

| Condition | Level | `client_outcome` |
|---|---|---|
| the exchange or the body errored and a timeout is in the cause chain | `WARN` | `timeout` |
| the exchange or the body errored | `ERROR` | `failure` |
| the subscription was cancelled (before the response, or mid-body) | `WARN` | `cancelled` |
| status ≥ 500 without an error signal (the peer answered) | `WARN` | `failure` |
| otherwise | `INFO` | `success` |
| … and the duration reached `slow-request-threshold` | `INFO → WARN` | unchanged, plus `client_slow: true` |

A 4xx is a `success` at INFO — the peer answered as designed; the status is on the line for the dashboard
to split by. Slowness raises severity; it never turns a completed call into a failure.

### 5.4 Meters

Six meters, all **consumed** from the host's `MeterRegistry`. All fixed-tag meters are **pre-registered
at construction** — including the `cancelled` outcome — so a `rate()` alert sees the zero before the
first occurrence.

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `client.logging.failopen` | counter | `stage` = `emission` \| `arrival` \| `wiring` | Logging failures the fail-open path swallowed. `emission`: an exchange event was **lost**. `arrival`: a start line was lost. `wiring`: bookkeeping failed — the event usually still follows. |
| `client.logging.events` | counter | `outcome` = `success` \| `failure` \| `timeout` \| `cancelled` | Exchange events actually **emitted** — after the level gate, arrival lines excluded. The reconciliation ground truth against the log index. |
| `client.logging.exchanges.open` | gauge | — | Exchanges between filter entry (wiring) and the exactly-once completion. Hovers near the in-flight call count in health. |
| `client.logging.correlation.id` | counter | `source` = `trace` \| `header` \| `generated` | Origin of each call's request id (ADR-0002). |
| `client.response.body.read` | counter | `uri`, `host`, `state` = `unread` \| `partial` \| `complete` | How far the application **consumed** the response body, opt-in via `measure-response-body-size`, recorded once per call that received a response and completed. `partial` = a subscription exists but no completion signal was observed (a `take`, a cancelled subscription, an error mid-stream). A `releaseBody()` subscribes and drains and counts as `complete`. |
| `client.request.body.size` / `client.response.body.size` | distribution summary, base unit `bytes` | `uri`, `host` | Bytes that **actually flowed**, opt-in via `measure-*-body-size`, independent of body logging and level. Exact beyond `max-body-bytes`. Zero-byte bodies record no sample. |

**Registration conflicts.** Micrometer rejects a registration whose id already exists with a different
meter type. Rather than aborting the context or suppressing an exchange event, the conflicting meter
falls back to a private registry, warned once per meter name on
`eu.inqudium.legatium.webclient.logging.ClientLoggingMetrics`.

### 5.5 Reading the meters together

| Question | Signal |
|---|---|
| Are exchange events being lost **loudly** (something threw)? | `failopen{stage=emission}` > 0 |
| Are exchange events being lost **silently** (a body nobody consumed)? | `exchanges.open` baseline grows monotonically instead of returning towards 0 |
| Is the **log pipeline** losing events? | `sum(client.logging.events)` over a window ≠ count of indexed `http-client-exchange` documents |
| Did the application stop propagating identity onto its calls? | the `generated` share of `correlation.id` rises (zero by construction with tracing configured — [§6.8](#68-tracing-makes-every-call-traced)) |
| Are callers cancelling their own calls (operator timeouts)? | `events{outcome=cancelled}` rises while `timeout` does not — [§6.3](#63-timeouts-connector-vs-operator) |
| Is a call site discarding the payload it paid for? | the `partial` share of `response.body.read{uri=...,host=...}` rises |

A suggested alert set:

```promql
# lost exchange events (hard failure)
increase(client_logging_failopen_total{stage="emission"}[5m]) > 0

# silently stuck exchanges (liveness) - tune the bound to the service's outbound concurrency
min_over_time(client_logging_exchanges_open[15m]) > 50

# callers tearing down their own calls
sum(rate(client_logging_events_total{outcome="cancelled"}[10m])) > 0
```

### 5.6 Trace correlation

The module reads the **outgoing W3C `traceparent` header**, put on the request by the host's tracing
propagation:

```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
                 └──────── traceId ───────────────┘ └──── spanId ────┘
```

- `traceId` is the trace the client span runs under, published under Boot's logging-correlation key
  `traceId`, so the log-to-trace join holds.
- The header's parent-id is the span the peer will treat as its parent — the local client span of this
  call — published under Boot's local-span key `spanId`. (Inbound, Limesium publishes the same header
  field as `parentSpanId`, because there it is the caller's span.)
- Parsing follows the W3C Trace Context Recommendation strictly; a non-conformant header is ignored and
  the call counts as traceless for the identity decision. The conformance is pinned by
  `traceparent/conformance.txt`, the same fixture Limesium uses.
- Since ADR-0002 the trace id also **is** the call's `client_request_id`, and a traced call gets no
  correlation header.

**Where the header comes from.** With Micrometer Tracing on the classpath, Boot's
`WebClientObservationAutoConfiguration` registers the client observation; `WebClient` starts it with the
request *builder* as the carrier — the tracing handler opens the client span and **injects `traceparent`
into the builder** — and only then builds the request and runs the filter chain, so every filter, this one
included, sees the header. The tracing integration test pins that order beside a real Brave bridge.

---

## 6. Special characteristics

### 6.1 Differences to the RestClient twin

Everything not listed here behaves exactly as in `legatium-restclient-logging`.

| Concern | RestClient twin | This module |
|---|---|---|
| Disposition vocabulary | `success` / `failure` / `timeout` | plus **`cancelled`** |
| Emission point | response `close()` | the response body's terminal signal; for a call without a response, the response `Mono`'s error/cancel signal |
| Never-completing exchange | a response never closed | a body never subscribed nor released |
| Request body | the byte array the client hands over | teed at the connector's `writeWith` through a wrapped inserter |
| Call-wide MDC | thread-local `MdcScope` around the wire call | none |
| Read failure mid-body | `IOException` from the tee stream | the body `Flux`'s error signal |
| Body tee concurrency | volatile single-writer | lock-guarded, frozen at emission |
| Attachment | `RestClientCustomizer` + `RestTemplateCustomizer` | `WebClientCustomizer` |
| Coroutine callers | n/a | the same filter — `awaitBody` and friends await the same body `Flux` |

### 6.2 Cancellation and the missing status

A subscriber that cancels — a downstream `timeout()` operator, a `take(1)`, a disposed `Disposable`, a
client disconnecting from a server that streams this call's result through — ends the exchange with a
CANCEL signal. The event is emitted immediately at WARN with `client_outcome=cancelled`: with the received
status when the response had arrived (a body cancelled mid-stream), with `-> -` and no status field when
it had not. Dashboards must treat `client_outcome` as the authoritative disposition and not assume the
status field is always present.

### 6.3 Timeouts: connector vs. operator

Two things are both called "timeout" and reach this filter as different signals:

- A timeout the **connector** raises — Reactor Netty's `responseTimeout`, a JDK connector's request
  timeout — arrives as an **error** whose cause chain carries a timeout type; the shared `Timeouts`
  classification recognises the JDK types and Netty's `io.netty.handler.timeout.TimeoutException` family
  by name (no Netty dependency in the module), and the event is `client_outcome=timeout` at WARN.
- A timeout the **caller** applies with the `timeout()` operator **cancels** the upstream subscription;
  this filter sees a CANCEL, never the `TimeoutException` the operator raises downstream, and the event is
  `client_outcome=cancelled`. That is truthful — from the exchange's point of view the caller walked away
  — and it is why the `cancelled` share is the number to watch when a service tunes its operator timeouts.

A host that wants every timeout to read `timeout` configures it on the connector, where it belongs. Pinned
by the integration test in both variants.

### 6.4 A body nobody consumes

The emission rests on the response body reaching a terminal signal. Every path of `WebClient` that hands
the response to application code guarantees that (`retrieve()` subscribes; `toBodilessEntity()`,
`exchangeToMono()` and `exchangeToFlux()` release what was not consumed). The one path that does not is
the deprecated raw `exchange()`: a caller that obtains the `ClientResponse` and drops it without
subscribing or releasing leaks the connection — and the exchange stays **open on the gauge**
`client.logging.exchanges.open`. A monotonically growing baseline is the signal for exactly that host bug,
visible before the connection pool runs dry.

### 6.5 Late body chunks after cancellation

Reactive Streams permits an already-requested `onNext` to arrive **after** a cancellation — on another
thread, after `doFinally` ran. The capture therefore does not rely on a single-writer assumption: every
mutation and read is under one lock, and the emitter's first step is `freeze()`. From then on a late tee
call is a no-op, so the logged body text and the size sample are one consistent snapshot instead of a
moving target.

### 6.6 The request body inserter is wrapped

`ClientRequest` is immutable and carries its body as a `BodyInserter` that is invoked by the connector
with the connector's own `ClientHttpRequest`. To observe the bytes, the filter rebuilds the request
(`ClientRequest.from(...)` copies method, URL, headers, cookies, attributes and the `httpRequest`
consumer) with an inserter that hands the original inserter a **decorated** connector request whose
`writeWith` / `writeAndFlushWith` tee the buffers. Consequences: the connector still receives the caller's
inserter output byte for byte; a `Mono` body keeps the single-buffer path; and the rebuild happens only
when the request body is logged or measured (or a correlation header must be added) — otherwise the
connector gets the caller's very request object.

### 6.7 Retries yield one line per attempt

The filter sits innermost ([§3.3](#33-filter-order-and-other-filters)), so a retrying filter — or a
`retryWhen` around the call, which re-subscribes the whole exchange — invokes it once per attempt. Each
attempt is a crossing and gets its own line, with the same `client_request_id` under a trace (or, on a
traceless call, the correlation header the first attempt added to the request the retry re-sends).

### 6.8 Tracing makes every call traced

With Micrometer Tracing configured, the client observation roots a trace whenever none is active on the
subscribing context, so **every** outbound call carries a `traceparent` — sampled or not. Consequences: the
module never generates a correlation id in such a host, `correlation.id{source=generated}` reads zero by
construction, and the peer never receives an `X-Correlation-Id` from this module. Pinned by the tracing
integration test.

### 6.9 One metrics instance per registry

Micrometer deduplicates meters by id. A second `ClientLoggingMetrics` instance against the same registry
would share the **counters** but not the **gauge**: the second gauge registration is silently ignored.
Every filter therefore obtains its metrics owner through a per-registry cache, so several filters on one
registry share one owner and the gauge reports the total across them.

### 6.10 Masking is a fingerprint, not a secret

`masked` replaces a header value with `length:sha256-prefix64` — stable across events, across the twins
and across the Limesium server line, with negligible accidental collisions. It is **unsalted and
unkeyed**: it prevents plaintext exposure, not offline guessing. Do not treat `masked` as a security
boundary for guessable values; omit such headers from the selection instead.

### 6.11 Shared code: legatium-common, inlined by Shade

The byte-identical part of the twins' shared layer lives in the `legatium-common` module
([ADR-0003](../../docs/adr/ADR-0003-legatium-common-inlined-by-shade.md)): the `Traceparent` parser (with
its tests and fuzz target), `HeaderLogProperties` (with unit test and fuzz target), the `ClientLogField`
enum with its builder extensions and the `ClientLoggingProperties` binding (ADR-0003 amendments),
`Timeouts`, `NanoTimeSource`,
`CorrelationIdGenerator`, `reportQuietly`/`failOpen`, the MDC keys and scope, and
`BodyReadState`/`decodeTruncated`. The Maven Shade plugin inlines those classes into THIS jar at package
time, the dependency-reduced POM drops the dependency, and `legatium-common` is never published —
consumers keep adding exactly one artifact, and the shared classes stay `internal` (`-Xfriend-paths`;
build from the reactor root or with `-am`).

Everything whose twin copies genuinely differ stays deliberately duplicated: the metrics (the
`cancelled` outcome, meter descriptions), the emitters and exchanges, filter vs. interceptor, and
`BoundedBodyCapture` (two different concurrency designs). A change there is a conscious port in both
directions; the lockstep tests catch *named* contract drift, not behavioural drift.

---

## 7. Appendix

### 7.1 File map

```
legatium-webclient-logging/
├── pom.xml                                   library deps only
├── README.md                                 module summary and the twin-difference table
├── docs/
│   ├── GUIDE.md                              this document
│   └── api-module.md                         the module page of the Dokka API reference
└── src/
    ├── main/kotlin/eu/inqudium/legatium/webclient/logging/
    │   ├── ClientLoggingAutoConfiguration.kt      beans, the late WebClientCustomizer
    │   ├── ClientRequestLoggingFilter.kt          the filter: activation, wiring, signal mapping, response mutation, complete
    │   ├── Exchange.kt                            per-exchange state, ExchangeState
    │   ├── ExchangeLogEmitter.kt                  arrival line and completion event
    │   ├── ClientLoggingMetrics.kt                the six meters (four outcomes)
    │   ├── CapturingDecorators.kt                 tee(), the request decorator, the inserter wrap
    │   └── BoundedBodyCapture.kt                  bounded, freezable capture target, read state
    │   (ClientLoggingProperties, ClientLogFields, Traceparent, Timeouts, Mdc, NanoTimeSource,
    │    CorrelationIdGenerator, HeaderLogProperties, BodyCapture helpers and the fail-open guards
    │    live in ../legatium-common - inlined, §6.11)
    ├── main/resources/META-INF/spring/…AutoConfiguration.imports
    └── test/kotlin/eu/inqudium/legatium/webclient/logging/  see the suite overview below
```

Test-suite overview (the generated [test-evidence page](https://inqudium.github.io/legatium/tests/test-evidence/)
lists every test with its rationale):

| Suite | Scope |
|---|---|
| Unit suites (`ClientRequestLoggingFilterTest`, `…BodyAndHeaderTest`, `…MetricsTest`, `BoundedBodyCaptureTest`) | hand-built request/response driven, every signal synchronous: line format, identity, levels/outcomes including `cancelled`, emission at the body's terminal signal, activation, tees, meters, fail-open stages |
| `ClientLoggingAutoConfigurationTest` | the shipped activation: beans, the customizer attaching the filter to Boot's builder, back-off, the optional-dependency boundary |
| `ClientRequestLoggingFilterIntegrationTest` | end to end through Boot's `WebClient.Builder` and Reactor Netty against a real HTTP peer: templates, bodies on pooled buffers, the wire correlation header, refused connection, the connector's response timeout, a downstream timeout operator |
| `ClientRequestLoggingTracingIntegrationTest` | ADR-0002 beside a real Brave bridge: the injected `traceparent`, the log-to-trace join, no correlation header on traced calls, every call traced |
| Lockstep/contract tests (`TwinContractTest`, `UriTemplateAttributeTest`) | pin the twin contracts and the mirrored `WebClient` attribute; the field/template and configuration/reference lockstep (`ClientLogFieldTest`, `ClientLoggingReferenceConfigTest`, `ClientLoggingPropertiesTest`) lives once in legatium-common |

Fuzzing of the shared `Traceparent` parser and header masking lives in legatium-common; the bounded
capture's fuzz target lives in the RestClient twin (the reactive capture adds a lock and a freeze around
the same arithmetic).

### 7.2 Related documents

- [`README.md`](../README.md) — module summary, the twin-difference table, the duplication decision.
- [`legatium-restclient-logging/README.md`](../../legatium-restclient-logging/README.md) — the reference
  implementation's documentation; everything not listed in [§6.1](#61-differences-to-the-restclient-twin)
  applies here unchanged.
- [`/docs/client-logging-reference.yml`](../../docs/client-logging-reference.yml) — the complete commented
  configuration reference, bound by both twins.
- [`/docs/elk/README.md`](../../docs/elk/README.md) — the Elasticsearch component template for the
  `client_*` fields.
- [`/docs/adr/`](../../docs/adr/) — the decision records.
- [Limesium](https://github.com/Inqudium/limesium) — the inbound sibling.
