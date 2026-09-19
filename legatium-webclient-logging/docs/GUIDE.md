# legatium-webclient-logging — Guide

One structured `adapter_*` log line per outbound HTTP exchange made through Spring's `WebClient` — with the
same message format, the same field family, the same `adapter-logging.*` configuration and the same meters
as the RestClient twin [`legatium-restclient-logging`](../../legatium-restclient-logging/README.md). The
inbound counterpart of the whole family is the sibling project
[Limesium](https://github.com/Inqudium/limesium).

This guide is the long-form companion to the module [README](../README.md). It explains what the module
does, how it is built, how it is wired into a foreign application, and which behaviours are specific to
the reactive client stack. Everything that is one contract for both twins — prerequisites, the
dependency, the beans, the exchange line and the logging backend, the configuration, the fields, the
meters, the fail-open promise and the shared code — is written once, in the
[Common guide](../../docs/GUIDE.md). Everything here is derived from the code under
`src/main/kotlin/eu/inqudium/legatium/webclient/logging/`; when the two disagree, the code wins.

## Table of contents

1. [Introduction](#1-introduction)
   1. [What the module does](#11-what-the-module-does)
   2. [Relation to the RestClient twin](#12-relation-to-the-restclient-twin)
2. [Architecture](#2-architecture)
   1. [Component overview](#21-component-overview)
   2. [Auto-configuration and registration](#22-auto-configuration-and-registration)
   3. [Lifecycle of one exchange](#23-lifecycle-of-one-exchange)
   4. [Emission point: the body's terminal signal](#24-emission-point-the-bodys-terminal-signal)
   5. [The body tees](#25-the-body-tees)
   6. [MDC and the reactive call](#26-mdc-and-the-reactive-call)
   7. [Fail-open contract](#27-fail-open-contract)
3. [Using it in a foreign project](#3-using-it-in-a-foreign-project)
   1. [Automatic wiring](#31-automatic-wiring)
   2. [Manual wiring](#32-manual-wiring)
   3. [Filter order and other filters](#33-filter-order-and-other-filters)
   4. [Verifying the integration](#34-verifying-the-integration)
   5. [Naming a client](#35-naming-a-client)
   6. [Joining the server line: what the host provides](#36-joining-the-server-line-what-the-host-provides)
4. [Special characteristics](#4-special-characteristics)
   1. [Differences to the RestClient twin](#41-differences-to-the-restclient-twin)
   2. [Cancellation and the missing status](#42-cancellation-and-the-missing-status)
   3. [Timeouts: connector vs. operator](#43-timeouts-connector-vs-operator)
   4. [A body nobody consumes](#44-a-body-nobody-consumes)
   5. [Late body chunks after cancellation](#45-late-body-chunks-after-cancellation)
   6. [The request body inserter is wrapped](#46-the-request-body-inserter-is-wrapped)
   7. [Retries yield one line per attempt](#47-retries-yield-one-line-per-attempt)
5. [Appendix](#5-appendix)
   1. [File map](#51-file-map)
   2. [Related documents](#52-related-documents)

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
emission, metrics — can ever fail, delay or alter the call it describes
([Common guide §8.2](../../docs/GUIDE.md#82-fail-open-contract)).

What the exchange line looks like — the message, the structured document, the arrival line — is
[Common guide §4](../../docs/GUIDE.md#4-logging-backend-and-structured-output); what the module
deliberately does not do (no rates or latencies as metrics, no retries, no body masking transformers, no
call-wide thread-local MDC, no hand-built clients) is
[Common guide §8.1](../../docs/GUIDE.md#81-what-the-modules-deliberately-do-not-do). A hand-built
`WebClient` gets the filter bean added by the host ([§3.2](#32-manual-wiring)); why the identity rides
the emission scope and the message rather than a call-wide MDC is
[§2.6](#26-mdc-and-the-reactive-call).

### 1.2 Relation to the RestClient twin

The module is the **WebClient twin** of `legatium-restclient-logging`: the RestClient module is the
reference implementation, this module owns the message text and the reactive stack's outcome vocabulary
(`cancelled` on top), and the cross-stack contract files — configuration reference, field family and
index mapping — live in the repository-shared `/docs`, bound by both builds. The contract and the
lockstep tests that pin it are
[Common guide §9.2](../../docs/GUIDE.md#92-the-twin-contract-and-its-lockstep-tests); what this
stack does differently is [§4.1](#41-differences-to-the-restclient-twin).

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
│     • response Mono: ObservedResponse — onResponse, DELIVERING → RESPONDED,  │
│       error / empty / cancel → complete                                       │
│     • response body (mutated ClientResponse): ObservedBody — tee, read state,│
│       terminal signal → complete  ◀ emission                                 │
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
| `ClientLoggingAutoConfiguration` | Registers the filter bean, the default `NanoTimeSource` / `CorrelationIdGenerator` / `HeaderValueMasker`, and — when Boot's `spring-boot-webclient` is present — a late `WebClientCustomizer` that appends the filter. |
| `ClientRequestLoggingFilter` | Everything that decides **what** is logged and counted: activation by host and path, fail-open wiring (identity, the rebuilt request with correlation header and body tee), the arrival line, the response mutation with the body hooks, the exactly-once `complete` and the cancel decision. |
| `ObservedResponse` | The response `Mono` operator: records and wraps the response, moves the state `OPEN → DELIVERING → RESPONDED` around the downstream's `onNext`, and completes the exchange itself for an error, an empty completion, or a cancel by the caller before the body owns it — including a cancel from another thread *during* the handover, which a `doFinally` would have ignored. |
| `Exchange` / `ExchangeState` | Per-exchange state between entry and emission; one atomic `OPEN → DELIVERING → RESPONDED → COMPLETED` state instead of loose flags. |
| `ExchangeLogEmitter` | Builds and emits the arrival line and the completion event; freezes the captures first; resolves level and outcome (timeouts via the shared `Timeouts`, the status half via the shared `Classification.ofStatus`, `cancelled` on top); records body sizes; restores the caller's context, then opens the emission `MdcScope` with trace ownership. |
| `AmbientContextRestorer` / `ContextPropagationRestorer` | Turns the exchange's captured `ContextView` back into thread-locals around an emission through Micrometer's context propagation (ADR-0010); detected by classpath presence, a no-op without the optional library. |
| `CapturingClientHttpRequestDecorator` / `tee` | The `DataBuffer` tee: wraps the connector's request while the inserter writes (a zero-copy-preserving variant when the connector offers `sendfile`); the same `tee` copies each response buffer. |
| `ObservedBody` | The response body operator: tees each buffer, marks the read state, turns the body's terminal signal into the exchange's completion, and tells a consumer's own stop (a cancel from within its delivery - Spring's body skip, a `take`) from an abandonment (`cancelled`). |
| `BoundedBodyCapture` | The lock-guarded, freezable capture target; count-only mode with limit `0`; the response-side read state (`BodyReadState`). |
| Shared layer (`legatium-common`, inlined) | `ClientLoggingProperties` / `HeaderLogProperties`, `ClientLogField`, `ClientLoggingMetrics`, `ClientActivation`, `MdcScope`, `Traceparent`, `Timeouts` (recognising Reactor Netty's timeouts by name), the injectable collaborators and the fail-open guards — one implementation for both twins, class by class in [Common guide §9.1](../../docs/GUIDE.md#91-the-shared-classes). |

### 2.2 Auto-configuration and registration

`ClientLoggingAutoConfiguration` is listed in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` and is conditional on
`adapter-logging.enabled` (default `true`) only — no web application type. It registers:

| Bean | Condition | Purpose |
|---|---|---|
| `NanoTimeSource` | `@ConditionalOnMissingBean` | `NanoTimeSource.SYSTEM` |
| `CorrelationIdGenerator` | `@ConditionalOnMissingBean` | `CorrelationIdGenerator.DEFAULT` (counting generator — ADR-0004) |
| `HeaderValueMasker` | `@ConditionalOnMissingBean` | `HeaderValueMasker.forKey(properties.maskingKey)` — the `length:hash` fingerprint, HMAC-keyed when `masking-key` is set; the one bean both twins mask with (the filter's constructor defaults to the same, so manual wiring honours the key too) |
| `ClientRequestLoggingFilter` | `@ConditionalOnMissingBean` | the filter, built from the bound properties and the host's `MeterRegistry` (`ObjectProvider`; an empty, no-op `CompositeMeterRegistry` without one) |
| `WebClientCustomizer` | `@ConditionalOnClass(WebClientCustomizer)`, `@Order(LOWEST_PRECEDENCE - 10)` | `builder.filter(filter)` on every `WebClient.Builder` Boot hands out |

Because the filter is its own bean, a host can replace it while keeping the customizer
([Common guide §3](../../docs/GUIDE.md#3-overriding-beans)). Boot's `spring-boot-webclient` module is an **optional** dependency:
without it the filter bean still exists and the host attaches it by hand ([§3.2](#32-manual-wiring)).

**Observing the wiring.** At DEBUG on the logger
`eu.inqudium.legatium.webclient.logging.ClientLoggingAutoConfiguration` the auto-configuration reports
what it did — the answer to "is the module on, and did it configure my client?" from the host's own log:

```
Adapter logging is enabled - the auto-configuration is active (adapter-logging.enabled is not false)
Adapter logging registered its ClientRequestLoggingFilter bean with ClientLoggingProperties(enabled=true, loggerName=adapter-http-exchange, …, maskingKey=<redacted>)
Adapter logging restores the caller's thread-locals (its MDC) around every exchange line from the Reactor Context - io.micrometer:context-propagation is on the classpath
Adapter logging registered its WebClientCustomizer - the filter is attached to every WebClient.Builder Boot hands out
Adapter logging found Boot's client observation with Micrometer Tracing wired for WebClient.Builder - every call built there goes out with a traceparent, its trace id is the request id and no X-Correlation-Id is generated
Adapter logging attached its filter to a WebClient.Builder behind 0 earlier filter(s)
```

The first five appear once at context start (the bean line and the restore line only when the bean is
the module's own, not a host's — [Common guide §3](../../docs/GUIDE.md#3-overriding-beans)). The
observation line is logged once every singleton exists and states whether Boot's client observation
and Micrometer Tracing are wired next to the module — the decision behind the identity contract of
[Common guide §7.6](../../docs/GUIDE.md#76-trace-correlation), which has no property: without a
tracing bridge it reads
`Adapter logging found Boot's client observation wired for WebClient.Builder but no Micrometer Tracing - calls are observed, not traced, so the module generates the request id and sends X-Correlation-Id on every call that carries no traceparent`,
without Boot's observation at all
`Adapter logging found no client observation - Boot's observation auto-configuration for WebClient.Builder is not active (no ObservationRegistry bean, or the observation module is absent); the module generates the request id and sends X-Correlation-Id on every call that carries no traceparent`.
It describes the wiring, not the fate of a call — a host can still switch the observation off per
client or build a client by hand. The restore
line is the outcome of the classpath detection of [§2.6](#26-mdc-and-the-reactive-call): without
`io.micrometer:context-propagation` it reads
`Adapter logging emits every exchange line with the completing thread's MDC only - io.micrometer:context-propagation is not on the classpath, so the caller's thread-locals are not restored`
— the one place a host can read this, since the opt-in has no property. The attach line appears once
per `WebClient.Builder` Boot hands out, and its count of earlier filters is the position the customizer
order gave the module ([§3.3](#33-filter-order-and-other-filters)). With `adapter-logging.enabled=false`
none of them appears; Boot's condition evaluation report (DEBUG on
`org.springframework.boot.autoconfigure`) then names the property as the reason. Enable it with
`logging.level.eu.inqudium.legatium.webclient.logging.ClientLoggingAutoConfiguration=DEBUG`, or
`logging.level.eu.inqudium.legatium=DEBUG` for both twins at once.

At **TRACE** the bean line is followed by where every `adapter-logging.*` value came from — Boot's
origin of each value it bound, one line per key, then every value of the same name a lower-precedence
source also holds, marked as shadowed and indented with `+- ` under the winner. The masking key is rendered redacted whatever its source; keys
no source sets are the class defaults and are not listed:

```
Adapter logging property adapter-logging.exclude-hosts[0] = pushgateway (origin: class path resource [application.yml] - 20:7)
Adapter logging property adapter-logging.logger-name = outbound (origin: class path resource [application-prod.yml] - 3:16)
+- Adapter logging property adapter-logging.logger-name = adapter-http-exchange (origin: class path resource [application.yml] - 12:16) is shadowed by class path resource [application-prod.yml] - 3:16
Adapter logging property adapter-logging.masking-key = <redacted> (origin: System Environment Property "ADAPTER_LOGGING_MASKING_KEY")
```

With no `adapter-logging.*` key anywhere the report is one line saying so. The same information, per
property source, is what the actuator's `env` endpoint shows for a key
(`/actuator/env/adapter-logging.logger-name`); the TRACE lines put it into the startup log of a host
without the actuator. The rendering lives once in `legatium-common`
([Common guide §9.1](../../docs/GUIDE.md#91-the-shared-classes)).
The same property namespace and
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
                       └─ Mono.defer { ObservedResponse(next.exchange(outgoing), exchange, …) }
                            onNext(response):  state OPEN → DELIVERING
                                               observed = onResponse(exchange, response)
                                               actual.onNext(observed)                 ← the downstream takes it
                                               state DELIVERING → RESPONDED            ← the body owns the exchange
                            onError(t):        exchange.failure = t; complete
                            onComplete():      empty (still OPEN) → failure "no response"; complete unless RESPONDED
                            cancel():          from another thread while OPEN or DELIVERING → cancelled, complete;
                                               from within the delivery (a `next()`) or once RESPONDED → ignored

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
| response `Mono` errors (connection refused, a connector timeout) | `ObservedResponse.onError` | immediately; `-> -`, no status |
| response `Mono` is cancelled before a response (a downstream `timeout()`, a disposed caller) | `ObservedResponse.cancel` in `OPEN` | immediately; `cancelled`, `-> -` |
| response `Mono` is cancelled from another thread **while the response is being handed to the downstream** (a cancelling downstream drops the response and never subscribes to the body) | `ObservedResponse.cancel` in `DELIVERING` | immediately; `cancelled` with the received status |
| response delivered, body completes | `doFinally` on the body flux | at completion — status, headers, body and duration final |
| response delivered, body errors (reset mid-stream) | `doFinally` on the body flux | at the error — `failure` with the received status |
| response delivered, body subscription cancelled (`take`, a timeout after the status line) | `doFinally` on the body flux | at the cancel — `cancelled` with the received status |
| response delivered, body never subscribed nor released | — | never: the exchange stays open on the gauge ([§4.4](#44-a-body-nobody-consumes)) |

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
- `tee` counts the full length of each `DataBuffer` **first**, then reads at most
  `capture.remainingCapacity()` bytes out of it with a **non-advancing** read (the read position is
  untouched), and returns the original buffer. Counting cannot throw, the copy can (an exotic
  `DataBuffer`) — in that order a copy that throws costs the logged text of that chunk, never the size
  sample. Ownership, pooling and release are exactly those of an undecorated exchange.

`BoundedBodyCapture` is the target: the shared byte-bounded buffer of at most `max-body-bytes`
([Common guide §9](../../docs/GUIDE.md#9-shared-code-legatium-common-inlined-by-shade)), a total byte
counter, and a `frozen` flag — all under one uncontended `ReentrantLock`. With limit `0` it runs in
**count-only** mode for the body-size meters: nothing is buffered, every byte is counted, `tee` copies
nothing.

The captures exist only when a body is logged (in any mode — `on-failure` needs the bytes before the outcome is known, [Common guide §6.3](../../docs/GUIDE.md#63-body-logging-and-body-measuring)) **or** measured; without either, the request goes to the
connector as the caller built it (unless a correlation header had to be added), and the response body is
mutated for the terminal hooks only.

**The capture mirrors consumption, not transmission.** The log shows exactly the bytes the application
read — no more. A response body the application never subscribes to is logged as absent; a body consumed
partially (`take`, a cancelled subscription) is captured to exactly that extent, and the `[truncated, N
bytes total]` note counts what flowed, not `Content-Length`. Because of that, the log cannot tell a body
the peer sent but the application dropped from one that was never sent; the counter
`adapter.response.body.read` ([Common guide §7.4](../../docs/GUIDE.md#74-meters)) exists for exactly that distinction — with the
reactive observation points: a `releaseBody()` (`toBodilessEntity()`, the release of what an
`exchangeToMono` handler left over — it subscribes and drains) counts as `complete` with its bytes on the
size sample, Spring's body skip for `bodyToMono(Void.class)` cancels after the first buffer and counts as
`partial`, an answer without a body (a 204, an empty body flux) completes at once and counts as
`complete`, and only a body nobody ever subscribed to would be `unread` (and, never completing, is not
counted at all — the gauge shows it). The blocking twin's `toBodilessEntity()` never opens the stream and
counts `unread` there; the counter's question is answered per route, against how that route's client is
written.

### 2.6 MDC and the reactive call

The MDC — SLF4J's *mapped diagnostic context* — is a map of strings the logging backend keeps **per
thread**. Whatever a thread puts there (`MDC.put("endpoint_request_id", …)`) rides along on every log
event that thread writes afterwards: a pattern layout prints it with `%X{key}`, a structured encoder
emits every entry as a field of the document. It is how a log line that says nothing about the request
it belongs to still ends up joined to that request in the index.

It is conventionally managed by a **request scope**: a servlet filter puts the request's identity into
the MDC when the request enters, the whole handler chain inherits it for free, and the filter removes the
keys when the request leaves — mandatory on a pooled thread, which would otherwise carry one request's
identity into the next. Limesium's servlet twin does exactly this with its chain-wide `MdcScope`, and the
RestClient twin of this project relies on it: the interceptor runs on that same thread, the wire call
blocks it, and the client line inherits the inbound identity because the thread never changed.

The convention rests on one assumption — **one request, one thread** — and a reactive client breaks
it. The thread that runs this filter is not the thread that receives the response, and neither is the
one that reads the body; the exchange line is written on whichever thread completes the body, usually a
Reactor Netty event loop that never ran the inbound request and carries none of its MDC. The module
therefore provides the `adapter_*` identity in two places that need no thread continuity:

| Place | Mechanism | Who sees it |
|---|---|---|
| Emission scope | `MdcScope` around the single `log()` call, trace keys owned | structured encoders emitting MDC fields on the exchange line and the arrival line |
| Message | inline `[adapter_request_id=…]` | plain-text appenders |

The emission scope is an **additive overlay**: whatever MDC the completing thread carries stays visible
beside the client identity; only the trace keys are owned (a parsed id is installed, an unparsed one
removed for the scope, so a stale bridge id on an event-loop thread never joins the event to a foreign
trace). Propagating the client identity *into* the caller's reactive operators is deliberately not
attempted: the caller's own context (its inbound request, its trace) is the identity that matters there,
and the host's context-propagation setup owns it.

What the module cannot get from the completing thread is the **caller's** context — the inbound
identity the client line should join to. For that it uses the one carrier a reactive chain has that is
independent of threads: the **Reactor Context** the caller subscribed with, captured at subscription
(`Mono.deferContextual`, one immutable reference, the same for every thread and every retry attempt)
and turned back into thread-locals around each emission
([ADR-0010](../../docs/adr/ADR-0010-reactive-twin-restores-the-callers-context.md)):

| Layer around the `log()` call | Mechanism | Owns |
|---|---|---|
| outer: the caller's context | `AmbientContextRestorer` — Micrometer's `ContextSnapshotFactory.setThreadLocalsFrom(ctx)` through the `ThreadLocalAccessor`s the host registered, previous values restored on close | nothing: additive, `clearMissing` off — a thread-local the context does not mention stays as the thread has it |
| inner: the module's identity | `MdcScope` | the trace keys — a bridge id an accessor restored never outranks the `traceparent` header's |

The restoration is opt-in by classpath presence — `io.micrometer:context-propagation` is an optional
dependency of this module; without it the emitter restores nothing and behaves as before ADR-0010. No
`adapter-logging.*` key exists for it, so the configuration stays identical to the RestClient twin's. A
restorer that throws (a host accessor failing on this thread) costs the ambient keys, counted as
`stage=wiring`, never the event. What reaches the Reactor Context in the first place, though, depends on
the kind of application the module runs in — the two cases below. In both, the host's part per key is
the same and is spelled out with code in [§3.6](#36-joining-the-server-line-what-the-host-provides).

#### 2.6.1 In a WebFlux application

In a WebFlux host **no thread owns the request**. The handler starts on one event-loop thread and its
operators may continue on others; a value put into the thread's MDC is meaningful only until the next
operator boundary. The identity of the inbound request therefore lives in the Reactor Context of the
handler chain, not in a thread: a `WebFilter` writes it with `contextWrite`, every operator of the
chain sees it, and Micrometer's context propagation restores it into the MDC around an operator when
the host asks for it. Limesium's reactive twin does this for its `endpoint_*` keys — it writes them
into the context under the MDC key names and registers a `ThreadLocalAccessor` per key.

Whether the host's *own* log statements inside operators see those keys is Boot's
`spring.reactor.context-propagation`: `auto` restores thread-locals around every operator, the default
`limited` only around `tap` and `handle`. That is the host's concern for the host's lines.

**Should the host set `spring.reactor.context-propagation=auto` here?** Not for this module: the
exchange line joins the server line under `limited` as under `auto`, because the module restores the
context around its own emission (ADR-0010). For the host's *own* lines inside ordinary operators —
a `map`, a `flatMap` — yes: only `auto` gives them the inbound identity, and Limesium's reactive twin
warns at startup when it is missing. A WebFlux host that joins its own handler lines has therefore set
it already, and this module adds no second reason. As in the servlet case, the module does not set the
property itself: it belongs to the host and changes the behaviour of every Reactor operator in the
application.

The `WebClient` call is part of the handler chain, so the context the filter captures at subscription
**is** the handler's context — including the inbound identity, if the host or Limesium put it there.
The module restores it on the completing event-loop thread around the exchange line, regardless of the
propagation mode: the client line joins the server line under `limited` as under `auto`; under `auto`
the restoration merely repeats what Reactor already did around the operator. A `WebClient` call made
outside any request chain — a scheduled job, a startup probe — subscribes with an empty context and
logs the module's own identity alone, which is the truth about such a call.

What the host provides: the identity in the context (a `WebFilter` with `contextWrite`), an accessor
per key, and the library — [§3.6](#36-joining-the-server-line-what-the-host-provides), steps 1 to 3;
with Limesium's reactive twin only step 3 remains.

#### 2.6.2 In a Tomcat (servlet) application

In a servlet host **the request owns its thread**: the servlet filter chain sets the MDC on the
request's thread and removes it at the end (Limesium's servlet twin, or the host's own filter), and
every line written on that thread in between is joined. A `WebClient` used from such a host — a
service that mixes `RestClient` for most calls with `WebClient` for a streaming one, or a Spring MVC
controller returning a `Mono` — is subscribed on the servlet thread, but its response and body arrive
on Reactor Netty's event-loop threads. With `.block()` the servlet thread waits while an event-loop
thread completes the exchange and writes the line.

Two things are missing there that the WebFlux case has for free: the completing thread has no MDC, and
the Reactor Context is **empty** — a servlet request has no Reactor Context of its own, nothing wrote
the inbound identity into the chain. The module's restorer can only restore what the context holds, so
the host has to carry the servlet thread's MDC into the context at subscription. That is a capture from
thread-locals into the context, the reverse direction of the restoration, and Reactor provides it
through the same accessors:

- **`spring.reactor.context-propagation=auto`.** With automatic propagation enabled, Reactor captures
  the registered thread-local values into the context on the blocking subscription methods — `block()`
  and `blockOptional()` on a `Mono`, `blockFirst()`, `blockLast()`, `toIterable()` and `toStream()` on
  a `Flux` — on the thread that calls them, which is the servlet thread with its MDC. Nothing to add at
  the call site. **This is not the default.** Spring Boot's default for the property is `limited`, under
  which no capture happens on `block()`; the host sets it explicitly in its `application.yml`:

  ```yaml
  spring:
    reactor:
      context-propagation: auto
  ```

  The module does not set it and never will: the property belongs to the host, it changes the
  behaviour of every Reactor operator in the application and has a cost, and a logging library must
  not switch that on silently. Without it — and without `contextCapture()` below — the Reactor Context
  stays empty in a servlet host, and the exchange line carries the module's own `adapter_*` identity
  alone.
- **`contextCapture()` on the chain**, for a host that keeps Boot's default `limited`, or that subscribes
  without blocking (a `Mono` returned from an MVC controller, a `subscribe()` of the host's own). The
  operator captures the registered thread-local values at subscription time, on the subscribing thread:

  ```kotlin
  val body =
      client
          .get()
          .uri("/things/{id}", id)
          .retrieve()
          .bodyToMono(String::class.java)
          .contextCapture()
          .block()
  ```

Both capture only keys that have a **`ThreadLocalAccessor`** registered — the same accessors the
restoration uses on the way back — so step 2 of [§3.6](#36-joining-the-server-line-what-the-host-provides)
applies in a servlet host as well, and here it is always the host's step: Limesium's *servlet* twin sets
the `endpoint_*` keys in the MDC but registers no accessors (accessors are a reactive concern, and its
reactive twin registers them), so a Tomcat host that wants `endpoint_request_id` on its `WebClient`
lines registers an accessor for it. Step 1 of §3.6 — writing the identity into the context — is what
`auto` or `contextCapture()` does here in place of a `WebFilter`.

The round trip is then: servlet filter → MDC on the servlet thread → captured into the Reactor Context
at subscription → restored by the module on the event-loop thread around the exchange line → the line
carries `endpoint_request_id` beside `adapter_request_id`, logged on a `reactor-http-*` thread. When an
exchange happens to complete on the servlet thread itself (an immediate answer, a mocked connector), the
MDC is already there, and the additive restorer leaves it as it is.

Two things are deliberately not offered: a thread-local snapshot taken by the module at subscription
(the reasons are in ADR-0010 — on the reactive stack the subscribing thread is not a reliable source,
and the capture into the context is the mechanism Reactor itself provides), and a hand-written copy of
the MDC into the context per call (`contextWrite { it.putAllMap(MDC.getCopyOfContextMap()) }` works,
but repeats what `contextCapture()` does through the accessors, without the restoration's key
discipline).

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
and the gauge does not leak. What the promise behind the stages is, where the reports go (the module's
own loggers, never the exchange logger), why the exchange log is an observability feature and not an
audit trail, and why the boundary is `Exception` and not `Throwable`, is one contract for both twins —
[Common guide §8.2](../../docs/GUIDE.md#82-fail-open-contract).

---

## 3. Using it in a foreign project

Everything that is one contract for both twins — prerequisites, the dependency, overriding beans, the
logging backend and structured output, the index mapping, the configuration and the metrics — is written
once, in the [Common guide](../../docs/GUIDE.md). This chapter holds what is specific to the filter:
how it is wired into a Boot application, how to wire it by hand, where it sits in the chain, and how to verify
the integration.

### 3.1 Automatic wiring

The shipped activation is not the filter bean but the customizer that attaches it. The hook is Boot's
**`WebClient.Builder` Spring bean**, defined by `WebClientAutoConfiguration` in the
`spring-boot-webclient` module:

1. Boot defines `WebClient.Builder` as a **prototype-scoped** bean — every injection point receives a
   fresh builder, so one adapter's `baseUrl` or default headers never leak into another's.
2. Before handing a builder out, Boot applies every `WebClientCustomizer` bean to it, in bean order.
3. This module contributes one such customizer, ordered at `Ordered.LOWEST_PRECEDENCE - 10`, that does
   exactly `builder.filter(clientRequestLoggingFilter)` — the filter lands at the **end** of the
   builder's filter list, innermost ([§3.3](#33-filter-order-and-other-filters)).

Consequently the rule for the host is: **every adapter obtains its client from the injected
`WebClient.Builder` bean.** Constructor injection is the usual form; a `@Bean` method parameter or a
`WebClient.Builder` obtained from the `ApplicationContext` is the same builder with the same
customizers applied.

```kotlin
@Service
class ThingsAdapter(builder: WebClient.Builder) {        // Boot's WebClient.Builder bean, injected
    private val client = builder
        .baseUrl("https://api.example.com")
        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .build()

    fun thing(id: Long): Mono<Thing> =
        client.get().uri("/things/{id}", id).retrieve().bodyToMono(Thing::class.java)
}
```

Covered by the automatic wiring:

- every `WebClient` built from an injected `WebClient.Builder`, however many `build()` calls the
  adapter makes on it;
- every HTTP service client group Boot builds through that builder (`@ImportHttpServices` with the
  WebClient variant) — the proxies' underlying client carries the filter like any other.

**Not** covered — these clients never meet Boot's customizers and therefore log nothing:

- `WebClient.create()` / `WebClient.create(baseUrl)`;
- the static `WebClient.builder()`;
- a builder the host constructs and then customises itself.

For those, [§3.2](#32-manual-wiring) applies.

The automatic wiring is conditional on two things, both pinned by `ClientLoggingAutoConfigurationTest`:
`adapter-logging.enabled` (default `true`; `false` removes the filter bean and the customizer together),
and Boot's `WebClientCustomizer` class being present (`@ConditionalOnClass`) — without
`spring-boot-webclient` the nested `WebClientCustomization` backs off silently while the filter bean
remains. The wiring itself is fail-open like everything else: a failure inside the filter's setup for
a call degrades that call to a pass-through with a `stage=wiring` report
([§2.7](#27-fail-open-contract)); the customizer cannot fail in a way that breaks the builder.

To confirm the attachment at runtime — in a test or a startup check — read the builder's filter list;
the module's filter must be the last entry:

```kotlin
val builder: WebClient.Builder = context.getBean(WebClient.Builder::class.java)
builder.filters { filters -> check(filters.last() is ClientRequestLoggingFilter) }
```

### 3.2 Manual wiring

The filter bean `ClientRequestLoggingFilter` exists in every enabled context; only its **attachment**
depends on Boot's builder. Attach it yourself when a client does not pass through that builder:

| Situation | Why the automatic wiring does not reach it |
|---|---|
| The host builds clients by hand — `WebClient.create(...)`, the static `WebClient.builder()`, or a builder it constructs itself | Boot's customizers run only on the `WebClient.Builder` bean Boot defines; a client built elsewhere never sees them |
| `spring-boot-webclient` is absent — the host depends on `spring-webflux` directly without a Boot starter for the client | the nested customizer configuration is `@ConditionalOnClass(WebClientCustomizer)` and backs off; there is no `WebClient.Builder` bean either, so every client is hand-built anyway |
| A builder obtained from Boot is customised **after** the customizers ran and the logging filter must stay innermost | filters the host appends on that builder land behind this one and run *inside* it ([§3.3](#33-filter-order-and-other-filters)); where the logged request must be what those later filters produce, the host takes over the ordering |
| A client is built outside a Spring context — a library's own client, an integration test without Boot | there is no context to hold the bean, so the filter is constructed directly (below) |

The mechanics are one line: inject the bean and append it as the **last** filter, so it sits closest
to the connector and sees the request as the peer receives it, once per attempt of any retry outside it:

```kotlin
@Configuration(proxyBeanMethods = false)
class ThingsClientConfiguration {
    @Bean
    fun thingsClient(loggingFilter: ClientRequestLoggingFilter, auth: AuthenticationFilter): WebClient =
        WebClient.builder()
            .baseUrl("https://api.example.com")
            .filter(auth)               // outside: its header is what gets logged
            .filter(loggingFilter)      // last = innermost, closest to the connector
            .build()
}
```

Rules for manual wiring:

- **Reuse the one bean; do not construct a second filter in a Boot context.** The meters are identified
  by name, so every filter on one `MeterRegistry` shares one metrics owner and the
  `adapter.logging.exchanges.open` gauge reports the total across them
  ([Common guide §7.4](../../docs/GUIDE.md#74-meters)). A second instance would not break anything,
  but it buys nothing.
- **Honour the switch.** With `adapter-logging.enabled=false` the bean does not exist, and a plain
  injection point fails to start the context. A client configuration that must survive the switch takes
  an `ObjectProvider<ClientRequestLoggingFilter>` and attaches the filter only if it is available:

  ```kotlin
  @Bean
  fun thingsClient(loggingFilter: ObjectProvider<ClientRequestLoggingFilter>): WebClient =
      WebClient.builder()
          .baseUrl("https://api.example.com")
          .also { builder -> loggingFilter.ifAvailable { builder.filter(it) } }
          .build()
  ```

- **Activation is not the host's business.** Host and path activation (`adapter-logging.exclude-hosts`,
  `include-path-patterns`, `exclude-path-prefixes`) is evaluated inside the filter
  ([Common guide §6.4](../../docs/GUIDE.md#64-activation-hosts-and-paths)), so a manually attached filter applies the same rules as an
  automatically attached one. There is no need to attach it selectively.
- **Ordering is the host's business.** The automatic wiring guarantees "innermost" by its late
  customizer; a manual `filter(...)` call is appended wherever it is made. Put it last.

Outside a Spring context the filter is constructed directly. The constructor takes the bound
properties, the time source, the id generator and a `MeterRegistry`, plus an optional trailing
`HeaderValueMasker` — when omitted, the masker the properties' `masking-key` selects, exactly as the
auto-configuration's default bean, so a configured key is honoured however the filter is built — all
defaults are public:

```kotlin
val filter = ClientRequestLoggingFilter(
    ClientLoggingProperties(),              // every default; or a copy(...) with the fields to change
    NanoTimeSource.SYSTEM,
    CorrelationIdGenerator.DEFAULT,
    SimpleMeterRegistry(),                  // or the registry the surrounding code owns
)
val client = WebClient.builder().baseUrl(url).filter(filter).build()
```

Everything else is unchanged by the way the filter was attached: emission point, outcomes, meters,
header sections, body capture and the fail-open contract behave exactly as under the automatic wiring —
the filter does not know how it got onto the chain.

### 3.3 Filter order and other filters

The customizer is ordered at `Ordered.LOWEST_PRECEDENCE - 10`, so the filter is appended **behind** the
filters of customizers ordered before that value and of the builder's own configuration, and runs
**inside** them — closest to the connector:

- an authentication filter outside it has already added its header, so the logged (and masked) request
  headers are what the peer receives;
- a retrying filter outside it (or a `retryWhen` around the call) invokes it once per attempt — one line
  per attempt ([§4.7](#47-retries-yield-one-line-per-attempt));
- filters a host adds **after** the customizers ran (directly on a builder it obtained from Boot) run
  inside this one and are outside that guarantee.

**"Earlier" means ordered earlier.** A `WebClientCustomizer` bean **without** an `@Order` sits at
`Ordered.LOWEST_PRECEDENCE` — *after* the module's `LOWEST_PRECEDENCE - 10` — and is applied later: its
filter is appended behind the logging filter and runs inside it. An authentication header added there is
not on the logged line, and a retry performed there is one line spanning all attempts. To have the module
observe a host filter, order its customizer before the module's, `@Order(0)` being the usual choice; the
auto-configuration test pins both positions. The room below the module's order is deliberate: a
customizer that must see the fully configured filter list (a diagnostics wrapper) has it.

The `traceparent` header is not affected by the order at all: the client observation Boot registers
injects it into the request builder **before** the request is built and the filter chain runs
([Common guide §7.6](../../docs/GUIDE.md#76-trace-correlation)).

Activation is evaluated **in the filter** (`shouldNotFilter`), so its semantics are byte-identical with
the RestClient twin.

### 3.4 Verifying the integration

0. Before the first call, start the application with
   `logging.level.eu.inqudium.legatium.webclient.logging.ClientLoggingAutoConfiguration=DEBUG` and
   expect the wiring report of [§2.2](#22-auto-configuration-and-registration): the "enabled" line, the
   bean line with the bound properties, the customizer line — and, as soon as the host's first client
   is built, `Adapter logging attached its filter to a WebClient.Builder behind N earlier filter(s)`.
   No attach line for a client means Boot never handed that client a customized builder: it was built
   by hand ([§3.2](#32-manual-wiring)). At TRACE instead of DEBUG the report also names the file, line
   or environment variable each `adapter-logging.*` value came from, and which values were shadowed.

1. Make any call through a Boot-built `WebClient`:

   ```kotlin
   webClientBuilder.baseUrl("https://httpbin.org").build().get().uri("/get").retrieve().bodyToMono(String::class.java).block()
   ```

   Expect one `adapter-http-exchange` line with `adapter_request_id=…`. Without tracing configured, the
   peer received an `X-Correlation-Id` with that id (httpbin echoes request headers in its body). With
   Micrometer Tracing configured, expect `traceId=… spanId=…` on the line and **no** `X-Correlation-Id`
   at the peer (ADR-0002).

2. Point the client at a closed port and confirm the exchange line with `-> -`,
   `adapter_outcome=failure` at ERROR with the cause attached.

3. Apply `.timeout(Duration.ofMillis(1))` to a call and confirm `adapter_outcome=cancelled` — then
   configure the connector's response timeout instead and confirm `adapter_outcome=timeout`
   ([§4.3](#43-timeouts-connector-vs-operator)).

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
one bucket. The filter cannot tell the clients apart from the request alone — the application can, and
it says so once per client through a request attribute the filter reads at wiring time
(`ClientRequestLoggingFilter.ADAPTER_NAME_ATTRIBUTE`,
[ADR-0009](../../docs/adr/ADR-0009-adapter-name-is-a-request-attribute.md)):

```kotlin
@Configuration(proxyBeanMethods = false)
class ClientsConfiguration {
    @Bean
    fun billingClient(builder: WebClient.Builder): WebClient =
        builder
            .baseUrl("http://localhost:15001/billing")
            .defaultRequest { it.attribute(ClientRequestLoggingFilter.ADAPTER_NAME_ATTRIBUTE, "billing") }
            .build()

    @Bean
    fun geoClient(builder: WebClient.Builder): WebClient =
        builder
            .baseUrl("http://localhost:15001/geo")
            .defaultRequest { it.attribute(ClientRequestLoggingFilter.ADAPTER_NAME_ATTRIBUTE, "geo-lookup") }
            .build()
}
```

`defaultRequest` runs for every request the client builds, so the attribute is on the `ClientRequest`
before the filter chain starts; a per-call `attribute(...)` on the request spec overrides it. The
attribute is read from the request as the filter receives it — a filter outside this one that rebuilds
the request keeps the attributes (`ClientRequest.from(...)` copies them), so the usual authentication
or retry filters do not lose the name. Every call of a named client then carries `adapter_name` on the
completion event and on the arrival line, and the three body meters carry the name as their `name` tag
(`UNNAMED` for a client nobody named). A blank value counts as no name; the value is otherwise the
host's vocabulary and is neither folded nor validated.

**Verifying it:** make one call through a named client and expect `adapter_name=billing` beside
`adapter_url_host=localhost:15001` on the exchange line, whose message then reads
`Adapter http exchange GET billing -> 200 [...]` — the name in place of the target, which stays on
`adapter_route` and the `adapter_url_*` fields; a call through an unnamed client carries no
`adapter_name` at all and keeps the target in the message. With `measure-response-body-size` on,
`curl -s localhost:8080/actuator/metrics/adapter.response.body.read` lists `name` among the available
tags.

The attribute string is the same on the RestClient twin, so a host carrying both jars names its
clients with one literal. The field itself and the meter tag are documented once, in the
[Common guide §7.7](../../docs/GUIDE.md#77-naming-a-client).

### 3.6 Joining the server line: what the host provides

The exchange line is written on the thread that completes the body — an event-loop thread that never
ran the inbound request. The module restores the caller's context there from the Reactor Context
([§2.6](#26-mdc-and-the-reactive-call),
[ADR-0010](../../docs/adr/ADR-0010-reactive-twin-restores-the-callers-context.md)), but it can only
restore what the host made restorable. Three things, the first two per key:

**1. The identity in the Reactor Context.** The value must be in the context the caller subscribes
with — Reactor's own cross-thread carrier — under the name the MDC will use. In a WebFlux host a
`WebFilter` puts it there for every handler of the request (in a servlet host, where the identity sits
in the servlet thread's MDC instead, this step is the capture into the context described in
[§2.6.2](#262-in-a-tomcat-servlet-application)):

```kotlin
@Component
class InboundIdentityFilter : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val tenant = exchange.request.headers.getFirst("X-Tenant") ?: "unknown"
        return chain.filter(exchange).contextWrite { it.put("tenant", tenant) }
    }
}
```

A caller that is not a WebFlux handler writes the same way on its own chain: `client.get()…
.bodyToMono(String::class.java).contextWrite { it.put("tenant", tenant) }`.

**2. A `ThreadLocalAccessor` for the key.** Micrometer's context propagation turns context entries
into thread-locals only through an accessor registered for the key; without one the entry stays in the
context and never reaches the MDC. Register one per key with the JVM-global `ContextRegistry`, once,
idempotently — a context refresh must not register it twice:

```kotlin
@Configuration(proxyBeanMethods = false)
class MdcContextPropagationConfiguration {
    @Bean
    fun tenantMdcAccessor(): InitializingBean =
        InitializingBean {
            val registry = ContextRegistry.getInstance()
            if (registry.threadLocalAccessors.none { it.key() == "tenant" }) {
                registry.registerThreadLocalAccessor(
                    "tenant",
                    { MDC.get("tenant") },
                    { value -> MDC.put("tenant", value) },
                    { MDC.remove("tenant") },
                )
            }
        }
}
```

The four-argument overload takes the getter, the setter and the reset; the key is both the context key
and the MDC key, so one name serves all three places.

**3. The library on the classpath.** `io.micrometer:context-propagation` — a dependency of Micrometer
Tracing, so a host with a tracing bridge already has it; a host without one adds it explicitly:

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>context-propagation</artifactId>
</dependency>
```

Without it the module restores nothing and the exchange line carries the module's own identity alone,
as before ADR-0010.

**Limesium does steps 1 and 2 for its keys.** A host running the sibling's reactive twin gets
`endpoint_request_id`, `endpoint_method` and `endpoint_route` joined onto every client line with nothing
to add beyond step 3 — Limesium writes them with `contextWrite` under the MDC key names and registers
an accessor per key when the library is present.

**On `spring.reactor.context-propagation=auto`.** The restoration itself does not need it: the module
restores around its own emission, so Boot's default `limited` suffices for the join in a WebFlux host.
`auto` remains what a host wants for its *own* log statements inside reactive operators; with it, the
restoration around the exchange line is redundant and harmless. In a **servlet** host `auto` plays a
different role — it is one of the two ways the servlet thread's MDC gets captured into the context at
subscription in the first place, the other being `contextCapture()` on the chain
([§2.6.2](#262-in-a-tomcat-servlet-application)).

**Verifying it:** make one call from inside a request that carries the key, and expect the key in the
exchange line's MDC (`%X{tenant}` in a pattern, a top-level field with structured logging) beside
`adapter_request_id` — on a `reactor-http-*` thread. If the key is missing: the value is not in the
context (step 1), no accessor is registered (step 2, check `ContextRegistry.getInstance().threadLocalAccessors`),
or the library is absent (step 3). A restorer that throws is reported once per exchange on
`eu.inqudium.legatium.webclient.logging.ExchangeLogEmitter` and counted as `stage=wiring` on
`adapter.logging.failopen`.

---

## 4. Special characteristics

### 4.1 Differences to the RestClient twin

Everything not listed here behaves exactly as in `legatium-restclient-logging`.

| Concern | RestClient twin | This module |
|---|---|---|
| Disposition vocabulary | `success` / `rejected` / `failure` / `timeout` | plus **`cancelled`** |
| Emission point | response `close()` | the response body's terminal signal; for a call without a response, the response `Mono`'s error/cancel signal |
| Never-completing exchange | a response never closed | a body never subscribed nor released |
| Request body | the byte array the client hands over | teed at the connector's `writeWith` through a wrapped inserter |
| Call-wide MDC | thread-local `MdcScope` around the wire call | none |
| The caller's context on the exchange line | present on the thread — the wire call blocks the caller's thread; for a close on **another** thread, the caller's MDC snapshot taken at wiring (ADR-0011) | restored from the **Reactor Context** around the emission through the host's `ThreadLocalAccessor`s ([§2.6](#26-mdc-and-the-reactive-call), ADR-0010); opt-in by `io.micrometer:context-propagation` on the classpath |
| Read failure mid-body | `IOException` from the tee stream | the body `Flux`'s error signal |
| Body tee concurrency | volatile single-writer | lock-guarded, frozen at emission |
| Attachment | `RestClientCustomizer` + `RestTemplateCustomizer` | `WebClientCustomizer` |
| Coroutine callers | n/a | the same filter — `awaitBody` and friends await the same body `Flux` |

### 4.2 Cancellation and the missing status

Two very different things reach the body publisher as a CANCEL signal, and the filter's own body operator
(`ObservedBody`) tells them apart by where the cancel comes from:

- **The consumer decided it has read enough — from within its own delivery.** Spring's body skip for
  `bodyToMono(Void.class)`, `toEntity(Void.class)` and an unsupported media type drains a body-carrying
  response through `takeWhile(release; false)`, which cancels upstream in `onNext` of the first buffer;
  a `take(n)` cancels in `onNext` of the n-th. The peer answered, the application chose not to read the
  rest: the exchange completes as **`success`** with the received status, and the read-state counter
  (`adapter.response.body.read{state=partial}`, opt-in) shows the body was not read to its end. Logging
  these as `cancelled` would flag every fire-and-forget call at WARN and, in `on-failure` body mode,
  write both bodies of a healthy call.
- **The caller walked away — from anywhere else.** A downstream `timeout()` operator's timer, a disposed
  `Disposable`, a client disconnecting from a server that streams this call's result through: the event
  is emitted immediately at WARN with **`adapter_outcome=cancelled`** — with the received status when
  the response had arrived (a body cancelled mid-stream), with `-> -` and no status field when it had
  not (a cancel of the response `Mono` before the connector answered).

**The limit of the thread-identity rule.** It reads *where* a cancel comes from, not *why*. A
`publishOn` (or any queueing operator) between the response body and an early-exiting consumer moves the
consumer's decision to another thread: `bodyToFlux(DataBuffer.class).publishOn(scheduler).take(1)`
cancels from the worker outside a delivery and logs **`cancelled`** although the consumer merely had
enough. Conversely a cancel that a limit raises from *within* the delivery — `DataBufferUtils.join`
exceeding `maxInMemorySize` — reads as the consumer's decision and logs `success` with the read state
`partial`, while the caller sees the limit's error. The paths `WebClient` itself builds (`retrieve()`,
`exchangeToMono()`, the codecs) never hop, so they are read correctly; a consumer that hops and then
stops early is a call site to expect `cancelled` from. The duration of a line is sampled *after* the
body's terminal signal was handed on, so it includes the consumer's synchronous terminal work (Spring's
decoder joining and decoding the body) — the counterpart of the blocking twin's response occupancy —
and a `retry` that resubscribes synchronously from `onError` writes the next attempt's lines before this
attempt's.

A cancel of the response `Mono` *after* the response was delivered (a host operator such as `next()`
between this filter and the client) is ignored: from then on the body owns the exchange. The handover
itself is a race the filter's response operator (`ObservedResponse`) decides atomically: the state moves
`OPEN → DELIVERING` before the downstream's `onNext` and `DELIVERING → RESPONDED` after it returned. A
cancel that arrives from **another thread** while the state is `DELIVERING` — a caller's timer or
disposal firing exactly as the response is handed on — is the caller walking away while the downstream
may be dropping the response; it completes the exchange as `cancelled` with the received status right
there, because a dropped response never gets its body subscribed and could otherwise never complete
(no event, the open-exchanges gauge one too high forever). A cancel from **within** the delivery on the
same thread — `next()` cancels upstream before it hands the value on — is the downstream taking the
response, and the body owns the exchange as usual. The same thread-identity rule `ObservedBody` applies
to the body. Dashboards must
treat `adapter_outcome` as the authoritative disposition and not assume the status field is always
present. A connector that completes **without** a response at all — a host filter swallowing an error
into `Mono.empty()` — is a `failure` at ERROR with the cause WebClient raises for the caller ("completed
without emitting a response"). Pinned by the filter's unit tests: Spring's skip, a `take`, an
out-of-band cancel, a `next()`, an empty completion, and a barrier-driven cancel from another thread
during the handover.

### 4.3 Timeouts: connector vs. operator

Two things are both called "timeout" and reach this filter as different signals:

- A timeout the **connector** raises — a response timeout while waiting for the status line, or a
  connect timeout while the TCP handshake never completes — arrives as an **error** whose cause chain
  carries a timeout type; the shared `Timeouts` classification recognises the JDK types as types and
  Netty's `io.netty.handler.timeout.TimeoutException` family plus `io.netty.channel.ConnectTimeoutException`
  by name (no Netty dependency in the module), and the event is `adapter_outcome=timeout` at WARN.
  What each connector really raises, pinned by the connector suites:

  | Connector | Response timeout | Connect timeout |
  |---|---|---|
  | Reactor Netty | `ReadTimeoutException` (a Netty `TimeoutException`) | `io.netty.channel.ConnectTimeoutException` — a `ConnectException`, matched by name |
  | JDK `HttpClient` | `HttpTimeoutException` | `HttpConnectTimeoutException` (an `HttpTimeoutException`) |
  | Jetty | `java.util.concurrent.TimeoutException` (idle timeout) | `SocketTimeoutException` |
  | Apache HttpComponents 5 | `SocketTimeoutException` | `ConnectTimeoutException` (a `SocketTimeoutException`) |

  A refused connection stays a `failure` on every connector — the suites keep it as the control.
- A timeout the **caller** applies with the `timeout()` operator **cancels** the upstream subscription;
  this filter sees a CANCEL, never the `TimeoutException` the operator raises downstream, and the event is
  `adapter_outcome=cancelled`. That is truthful — from the exchange's point of view the caller walked away
  — and it is why the `cancelled` share is the number to watch when a service tunes its operator timeouts.

A host that wants every timeout to read `timeout` configures it on the connector, where it belongs. Pinned
by the Reactor Netty integration test in both variants and by the connector suites for the connector side.
A `timeout()` operator that fires *mid-body* cancels the body from its timer thread — out of band — and
reads `cancelled` with the received status ([§4.2](#42-cancellation-and-the-missing-status)).

### 4.4 A body nobody consumes

The emission rests on the response body reaching a terminal signal. Every path of `WebClient` that hands
the response to application code guarantees that (`retrieve()` subscribes; `toBodilessEntity()`,
`exchangeToMono()` and `exchangeToFlux()` release what was not consumed). The one path that does not is
the deprecated raw `exchange()`: a caller that obtains the `ClientResponse` and drops it without
subscribing or releasing leaks the connection — and the exchange stays **open on the gauge**
`adapter.logging.exchanges.open`. A monotonically growing baseline is the signal for exactly that host bug,
visible before the connection pool runs dry.

### 4.5 Late body chunks after cancellation

Reactive Streams permits an already-requested `onNext` to arrive **after** a cancellation — on another
thread, after `doFinally` ran. The capture therefore does not rely on a single-writer assumption: every
mutation and read is under one lock, and the emitter's first step is `freeze()`. From then on a late tee
call is a no-op, so the logged body text and the size sample are one consistent snapshot instead of a
moving target.

### 4.6 The request body inserter is wrapped

`ClientRequest` is immutable and carries its body as a `BodyInserter` that is invoked by the connector
with the connector's own `ClientHttpRequest`. To observe the bytes, the filter rebuilds the request
(`ClientRequest.from(...)` copies method, URL, headers, cookies, attributes and the `httpRequest`
consumer) with an inserter that hands the original inserter a **decorated** connector request whose
`writeWith` / `writeAndFlushWith` tee the buffers. Consequences: the connector still receives the caller's
inserter output byte for byte; a `Mono` body keeps the single-buffer path; and the rebuild happens only
when the request body is logged or measured (or a correlation header must be added) — otherwise the
connector gets the caller's very request object.

### 4.7 Retries yield one line per attempt

The filter sits innermost ([§3.3](#33-filter-order-and-other-filters)), so a retrying filter — or a
`retryWhen` around the call, which re-subscribes the whole exchange — invokes it once per attempt. Each
attempt is a crossing and gets its own line, with the same `adapter_request_id` under a trace. On a
**traceless** call each attempt generates and sends a fresh id: the retry re-subscribes with the caller's
immutable `ClientRequest`, which never carries the correlation header the filter adds to its rebuilt
copy — a documented difference from the blocking twin, whose mutable request keeps the header of attempt
1 (the filter's class documentation lists it).

**The connector's own retry is not an attempt.** Reactor Netty retries a request once, below the filter,
when a pooled connection turns out to be stale — but only while **no headers have been sent** (its
`HttpClientConnect` sets `shouldRetry = false` and warns "cannot be retried as the headers/body were
sent" otherwise). The request tee runs when the body emits inside `writeWith`, which is never before the
headers go out: a `bodyValue` body is marked and written together with its headers on the event-loop
thread, a streamed or asynchronous body sends its headers first. So the connector's retry can only fire
before the tee has run at all, and the body is counted and logged once per attempt of the filter,
never twice. Probed on 2026-09-17 against Reactor Netty 1.3.7 (`docs/assessment/RETRY_PROBE-2026-09-17T19-11-37.md`
in the repository; the assessment trail is not part of the site): a pooled
connection the peer closes after receiving the request is one line — `failure` with
`PrematureCloseException`, the body counted once — and an asynchronous body whose connection dies before
it emits is refused the retry because the headers were already out.

Tracing making every call traced, the one-metrics-owner-per-registry rule and the masking fingerprint are
one behaviour for both twins — [Common guide §7.6](../../docs/GUIDE.md#76-trace-correlation),
[§7.4](../../docs/GUIDE.md#74-meters) and [§6.2](../../docs/GUIDE.md#62-header-sections); the shared
code they rest on is [Common guide §9](../../docs/GUIDE.md#9-shared-code-legatium-common-inlined-by-shade).

---

## 5. Appendix

### 5.1 File map

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
    │   ├── ClientRequestLoggingFilter.kt          the filter: activation, wiring, response mutation, complete, the cancel decision
    │   ├── Exchange.kt                            per-exchange state, ExchangeState
    │   ├── ExchangeLogEmitter.kt                  arrival line and completion event
    │   ├── CapturingDecorators.kt                 tee(), the request decorator, the inserter wrap
    │   ├── ObservedResponse.kt                    the response Mono operator: state handover around the downstream's onNext, error/empty/cancel
    │   ├── ObservedBody.kt                        the response body operator: tee, read state, terminal signal, consumption vs. cancel
    │   └── BoundedBodyCapture.kt                  bounded, freezable capture target, read state
    │   (ClientLoggingProperties, ClientLogFields, Traceparent, Timeouts, Mdc, NanoTimeSource,
    │    CorrelationIdGenerator, HeaderLogProperties, BodyCapture helpers and the fail-open guards
    │    live in ../legatium-common - inlined, Common guide §9)
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
| Connector suites (`ConnectorContract` run as `ReactorNettyConnectorIntegrationTest`, `JdkHttpClientConnectorIntegrationTest`, `JettyConnectorIntegrationTest`, `HttpComponentsConnectorIntegrationTest`) | the connector-agnosticism contract against every connector Spring ships: the body tees on the engine's own buffers and the wire correlation header, the engine's real response and connect timeout types classified as `timeout` (the connect timeout provoked by a loopback tarpit, `Tarpit`), a refused connection as the `failure` control |
| `ClientRequestLoggingTracingIntegrationTest` | ADR-0002 beside a real Brave bridge: the injected `traceparent`, the log-to-trace join, no correlation header on traced calls, every call traced |
| Lockstep/contract tests (`TwinContractTest`, `UriTemplateAttributeTest`) | pin the message text, this stack's outcome vocabulary and the mirrored `WebClient` attribute; the shared literals (`SharedContractTest`), the field/template and configuration/reference lockstep (`ClientLogFieldTest`, `ClientLoggingReferenceConfigTest`, `ClientLoggingPropertiesTest`) and the metrics owner's registration behaviour (`ClientLoggingMetricsTest`) live once in legatium-common; the shaded jar itself is exercised by the standalone `consumer-smoke/` build |

Fuzzing of the shared `Traceparent` parser and header masking lives in legatium-common; the bounded
capture's fuzz target lives in the RestClient twin (the reactive capture adds a lock and a freeze around
the same arithmetic).

### 5.2 Related documents

- [Common guide](../../docs/GUIDE.md) — everything that is one contract for both twins: prerequisites,
  dependency, beans, logging backend, index mapping, configuration, fields, MDC keys, meters, trace
  correlation, scope and fail-open guarantees, the shared code.
- [`README.md`](../README.md) — module summary, the twin-difference table, the duplication decision.
- [`legatium-restclient-logging/README.md`](../../legatium-restclient-logging/README.md) — the reference
  implementation's documentation; everything not listed in [§4.1](#41-differences-to-the-restclient-twin)
  applies here unchanged.
- [`/docs/adapter-logging-reference.yml`](../../docs/adapter-logging-reference.yml) — the complete commented
  configuration reference, bound by both twins.
- [`/docs/elk/README.md`](../../docs/elk/README.md) — the Elasticsearch component template for the
  `adapter_*` fields.
- [`/docs/adr/`](../../docs/adr/) — the decision records, among them the outcome gate on bodies (ADR-0006) and the `adapter` vocabulary (ADR-0007).
- [Limesium](https://github.com/Inqudium/limesium) — the inbound sibling.
