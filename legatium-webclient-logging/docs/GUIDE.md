# legatium-webclient-logging — Guide

One structured `adapter_*` log line per outbound HTTP exchange made through Spring's `WebClient` — with the
same message format, the same field family, the same `adapter-logging.*` configuration and the same meters
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
   1. [Automatic wiring](#31-automatic-wiring)
   2. [Manual wiring](#32-manual-wiring)
   3. [Filter order and other filters](#33-filter-order-and-other-filters)
   4. [Verifying the integration](#34-verifying-the-integration)
4. [Special characteristics](#4-special-characteristics)
   1. [Differences to the RestClient twin](#41-differences-to-the-restclient-twin)
   2. [Cancellation and the missing status](#42-cancellation-and-the-missing-status)
   3. [Timeouts: connector vs. operator](#43-timeouts-connector-vs-operator)
   4. [A body nobody consumes](#44-a-body-nobody-consumes)
   5. [Late body chunks after cancellation](#45-late-body-chunks-after-cancellation)
   6. [The request body inserter is wrapped](#46-the-request-body-inserter-is-wrapped)
   7. [Retries yield one line per attempt](#47-retries-yield-one-line-per-attempt)
   8. [Tracing makes every call traced](#48-tracing-makes-every-call-traced)
   9. [One metrics instance per registry](#49-one-metrics-instance-per-registry)
   10. [Masking is a fingerprint, not a secret](#410-masking-is-a-fingerprint-not-a-secret)
   11. [Shared code: legatium-common, inlined by Shade](#411-shared-code-legatium-common-inlined-by-shade)
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
emission, metrics — can ever fail, delay or alter the call it describes.

### 1.2 What the module deliberately does not do

- **No request rates, latencies or status distributions as metrics.** Boot's `http.client.requests` and
  the structured log fields cover those; the module's meters observe only what those cannot show
  ([Legatium guide §7.4](../../docs/GUIDE.md#74-meters)).
- **No retries, no circuit breaking, no request rewriting.** The module observes; the one thing it adds to
  a request is the correlation header on a traceless call without one ([Legatium guide §7.6](../../docs/GUIDE.md#76-trace-correlation)).
- **No body masking transformers and no per-key response sampling.** Bodies are logged verbatim up to the
  capture limit; the logger level is the only volume control ([Legatium guide §6.5](../../docs/GUIDE.md#65-logger-levels)).
- **No exporting of a `MeterRegistry`.** The host's registry is consumed if present; otherwise a private
  `SimpleMeterRegistry` absorbs the values.
- **No call-wide thread-local MDC.** A reactive call hops event-loop threads; the identity rides the
  emission scope and the message ([§2.6](#26-mdc-and-the-reactive-call)).
- **No clients built by hand.** The customizer covers every client built through Boot's builder (and the
  HTTP service client groups built from it); a hand-built `WebClient` gets the filter bean added by the
  host ([§3.2](#32-manual-wiring)).

### 1.3 The exchange line

On the logger `http-adapter-exchange` (configurable) a completed exchange looks like this in a plain-text
appender:

```
Adapter http exchange POST https://api.example.com/things/42 -> 200 [adapter_request_id=4bf92f3577b34da6a3ce929d0e0e4736 traceId=4bf92f3577b34da6a3ce929d0e0e4736 spanId=00f067aa0ba902b7]
```

The trace suffix appears only when the outgoing request carried a conformant W3C `traceparent` header —
its trace id then doubles as the request id (ADR-0002). Alongside the message, the event carries SLF4J
key-values that a structured encoder turns into fields:

```json
{
  "message": "Adapter http exchange POST https://api.example.com/things/42 -> 200 [adapter_request_id=4bf92f3577b34da6a3ce929d0e0e4736 traceId=4bf92f3577b34da6a3ce929d0e0e4736 spanId=00f067aa0ba902b7]",
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
  "spanId": "00f067aa0ba902b7"
}
```

The `adapter_request_id` / `adapter_method` / `adapter_route` / `traceId` / `spanId` entries come from the
MDC ([Legatium guide §7.2](../../docs/GUIDE.md#72-mdc-keys)); the `adapter_*` key-values are the field family of [Legatium guide §7.1](../../docs/GUIDE.md#71-log-fields). How
MDC entries land in the document (flat, nested, renamed) is the encoder's decision.

With the optional arrival line enabled, a second, earlier line precedes it:

```
Adapter http exchange started POST https://api.example.com/things/42 [adapter_request_id=4bf92f…]
```

The arrival line carries no outcome, status or duration, so a dashboard keyed on `adapter_outcome` still
sees exactly one event per call.

### 1.4 Relation to the RestClient twin

The module is the **WebClient twin** of `legatium-restclient-logging`. The RestClient module is the
reference implementation and owns the cross-stack contract:

| Contract | Owner | Lockstep test in this module |
|---|---|---|
| Configuration keys and defaults | [`/docs/adapter-logging-reference.yml`](../../docs/adapter-logging-reference.yml) | `ClientLoggingReferenceConfigTest` in `legatium-common` (one `ClientLoggingProperties` class for both twins, bound against the YAML once) |
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
| `ClientLoggingProperties` | The `adapter-logging.*` binding, validated in `init` - shared (legatium-common - §6.11), the very class the RestClient twin binds. `HeaderLogProperties` (shared too) is one header section. |
| `ClientRequestLoggingFilter` | Everything that decides **what** is logged and counted: activation by host and path, fail-open wiring (identity, the rebuilt request with correlation header and body tee), the arrival line, the signal mapping of the response `Mono`, the response mutation with the body hooks, the exactly-once `complete`. |
| `Exchange` / `ExchangeState` | Per-exchange state between entry and emission; one atomic `OPEN → RESPONDED → COMPLETED` state instead of loose flags. |
| `ExchangeLogEmitter` | Builds and emits the arrival line and the completion event; freezes the captures first; resolves level and outcome (timeouts via the shared `Timeouts`, `cancelled` on top); records body sizes; opens the emission `MdcScope` with trace ownership. |
| `ClientLogField` | The wire names and the exact JVM type of each structured field; a wrongly typed value drops the field with a warning, never the event. Shared (legatium-common): one enum for both twins. |
| `ClientLoggingMetrics` (shared, `legatium-common`) | The six meters, one implementation for both twins parameterised by the `ClientStack` (outcome vocabulary, `client` tag) - the fixed-tag meters pre-registered, the body meters created lazily per tag, per-meter fallback to a private registry on registration conflict. |
| `ClientActivation` (shared, `legatium-common`) | Which calls are logged at all: host exclusion, include patterns, exclude prefixes - one implementation, so the semantics are identical on both stacks by construction. |
| `CapturingClientHttpRequestDecorator` / `tee` | The `DataBuffer` tee: wraps the connector's request while the inserter writes (a zero-copy-preserving variant when the connector offers `sendfile`); the same `tee` copies each response buffer. |
| `ObservedBody` | The response body operator: tees each buffer, marks the read state, turns the body's terminal signal into the exchange's completion, and tells a consumer's own stop (a cancel from within its delivery - Spring's body skip, a `take`) from an abandonment (`cancelled`). |
| `BoundedBodyCapture` | The lock-guarded, freezable capture target; count-only mode with limit `0`; the response-side read state (`BodyReadState`). |
| `MdcScope` | Puts identity and trace keys into the MDC for the duration of one emission and restores the previous values. |
| `Traceparent` / `Timeouts` | Strict W3C `traceparent` parsing to `(traceId, spanId)`; the cause-chain walk that classifies a failure as a timeout — recognising Reactor Netty's timeout by name. |
| `NanoTimeSource` / `CorrelationIdGenerator` / `HeaderValueMasker` | Injectable time, id and header masking; `SYSTEM` and the two `DEFAULT`s are the production defaults. |
| `reportQuietly` / `failOpen` | Guard the diagnostics channel (counter + internal log) of every catch block. |

### 2.2 Auto-configuration and registration

`ClientLoggingAutoConfiguration` is listed in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` and is conditional on
`adapter-logging.enabled` (default `true`) only — no web application type. It registers:

| Bean | Condition | Purpose |
|---|---|---|
| `NanoTimeSource` | `@ConditionalOnMissingBean` | `NanoTimeSource.SYSTEM` |
| `CorrelationIdGenerator` | `@ConditionalOnMissingBean` | `CorrelationIdGenerator.DEFAULT` (counting generator — ADR-0004) |
| `HeaderValueMasker` | `@ConditionalOnMissingBean` | `HeaderValueMasker.DEFAULT` (the `length:hash` fingerprint); the one bean both twins mask with |
| `ClientRequestLoggingFilter` | `@ConditionalOnMissingBean` | the filter, built from the bound properties and the host's `MeterRegistry` (`ObjectProvider`; private `SimpleMeterRegistry` without one) |
| `WebClientCustomizer` | `@ConditionalOnClass(WebClientCustomizer)`, `@Order(LOWEST_PRECEDENCE - 10)` | `builder.filter(filter)` on every `WebClient.Builder` Boot hands out |

Because the filter is its own bean, a host can replace it while keeping the customizer
([Legatium guide §3](../../docs/GUIDE.md#3-overriding-beans)). Boot's `spring-boot-webclient` module is an **optional** dependency:
without it the filter bean still exists and the host attaches it by hand ([§3.2](#32-manual-wiring)).
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
- `tee` reads at most `capture.remainingCapacity()` bytes out of each `DataBuffer` with a
  **non-advancing** read (the read position is untouched), counts the full length, and returns the
  original buffer. Ownership, pooling and release are exactly those of an undecorated exchange.

`BoundedBodyCapture` is the target: a `ByteArrayOutputStream` of at most `max-body-bytes`, a total byte
counter, and a `frozen` flag — all under one uncontended `ReentrantLock`. With limit `0` it runs in
**count-only** mode for the body-size meters: nothing is buffered, every byte is counted, `tee` copies
nothing.

The captures exist only when a body is logged (in any mode — `on-failure` needs the bytes before the outcome is known, [Legatium guide §6.3](../../docs/GUIDE.md#63-body-logging-and-body-measuring)) **or** measured; without either, the request goes to the
connector as the caller built it (unless a correlation header had to be added), and the response body is
mutated for the terminal hooks only.

**The capture mirrors consumption, not transmission.** The log shows exactly the bytes the application
read — no more. A response body the application never subscribes to is logged as absent; a body consumed
partially (`take`, a cancelled subscription) is captured to exactly that extent, and the `[truncated, N
bytes total]` note counts what flowed, not `Content-Length`. Because of that, the log cannot tell a body
the peer sent but the application dropped from one that was never sent; the counter
`adapter.response.body.read` ([Legatium guide §7.4](../../docs/GUIDE.md#74-meters)) exists for exactly that distinction — where a
`releaseBody()` (which subscribes and drains) counts as `complete`, and only a body nobody ever
subscribed to would be `unread` (and, never completing, is not counted at all — the gauge shows it).

### 2.6 MDC and the reactive call

There is no call-wide thread-local MDC in a reactive client: the thread that runs the filter is not the
thread that receives the response, and neither is the one that reads the body. The module provides the
`adapter_*` identity in two places:

| Place | Mechanism | Who sees it |
|---|---|---|
| Emission scope | `MdcScope` around the single `log()` call, trace keys owned | structured encoders emitting MDC fields on the exchange line and the arrival line |
| Message | inline `[adapter_request_id=…]` | plain-text appenders |

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
`eu.inqudium.legatium.common.ClientLoggingMetrics`), never on the exchange logger, so the exchange stream stays parseable.

**Security note.** Fail-open is the inverse of what an audit log needs: a host-side fault silently
removes the call from the log instead of failing it. The exchange log is therefore an **observability**
feature with no completeness guarantee; a regulatory audit trail of outbound calls must come from a
fail-closed component. The compensating controls are `adapter.logging.failopen` and the
`exchanges.open` gauge ([Legatium guide §7.5](../../docs/GUIDE.md#75-reading-the-meters-together)) — alert on them.

**The boundary is `Exception`, not `Throwable` — a decision.** Every guard confines an `Exception` and
lets an `Error` propagate: a `VirtualMachineError`, a `LinkageError` from a broken logging backend or a
`StackOverflowError` is a JVM-level condition no logging library can meaningfully absorb, and swallowing
it would hide a process that is already failing. The one thing the module protects against an `Error` is
its own bookkeeping: a connector call that dies with an `Error` while assembling still closes the
open-exchange gauge (`abandonExchange`, no emission attempted, one WARN breadcrumb), so the liveness
signal cannot drift over something the module never caused.

### 2.8 Injectable collaborators

Time and randomness are injected, not ambient:

- `NanoTimeSource` — monotonic nanoseconds for `adapter_duration_ms` and the slow threshold; the single
  production read of `System.nanoTime()` is `NanoTimeSource.SYSTEM`.
- `CorrelationIdGenerator` — the id for traceless calls without a correlation header; `DEFAULT` (the
  counting generator, ADR-0004) by default: one atomic increment per call, no `SecureRandom` on the
  event loop. Never consulted for a traced call, and in a host with tracing configured never at all
  ([§4.8](#48-tracing-makes-every-call-traced)).

- `HeaderValueMasker` — how a header listed in a `masked` section renders on the line; `DEFAULT` is the
  stable `length:hash` fingerprint ([§4.10](#410-masking-is-a-fingerprint-not-a-secret)). The
  properties decide WHICH values are masked, the bean decides HOW - a keyed HMAC for a compliance regime,
  a fixed `***` for a host that wants no correlation at all.

All three are `fun interface`s, all three are `@ConditionalOnMissingBean` beans, and all are what the
module's tests drive from an `AtomicLong` / a fixed string / a lambda without any mocking library.

---

## 3. Using it in a foreign project

Everything that is one contract for both twins — prerequisites, the dependency, overriding beans, the
logging backend and structured output, the index mapping, the configuration and the metrics — is written
once, in the [Legatium guide](../../docs/GUIDE.md). This chapter holds what is specific to the filter:
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
  ([§4.9](#49-one-metrics-instance-per-registry)). A second instance would not break anything, but it
  buys nothing.
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
  ([Legatium guide §6.4](../../docs/GUIDE.md#64-activation-hosts-and-paths)), so a manually attached filter applies the same rules as an
  automatically attached one. There is no need to attach it selectively.
- **Ordering is the host's business.** The automatic wiring guarantees "innermost" by its late
  customizer; a manual `filter(...)` call is appended wherever it is made. Put it last.

Outside a Spring context the filter is constructed directly. The constructor takes the bound
properties, the time source, the id generator and a `MeterRegistry`, plus an optional trailing
`HeaderValueMasker` (the built-in fingerprint when omitted) — all defaults are public:

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
filters of earlier customizers and of the builder's own configuration, and runs **inside** them —
closest to the connector:

- an authentication filter outside it has already added its header, so the logged (and masked) request
  headers are what the peer receives;
- a retrying filter outside it (or a `retryWhen` around the call) invokes it once per attempt — one line
  per attempt ([§4.7](#47-retries-yield-one-line-per-attempt));
- filters a host adds **after** the customizers ran (directly on a builder it obtained from Boot) run
  inside this one and are outside that guarantee.

The `traceparent` header is not affected by the order at all: the client observation Boot registers
injects it into the request builder **before** the request is built and the filter chain runs
([Legatium guide §7.6](../../docs/GUIDE.md#76-trace-correlation)).

Activation is evaluated **in the filter** (`shouldNotFilter`), so its semantics are byte-identical with
the RestClient twin.

### 3.4 Verifying the integration

1. Make any call through a Boot-built `WebClient`:

   ```kotlin
   webClientBuilder.baseUrl("https://httpbin.org").build().get().uri("/get").retrieve().bodyToMono(String::class.java).block()
   ```

   Expect one `http-adapter-exchange` line with `adapter_request_id=…`. Without tracing configured, the
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

---

## 4. Special characteristics

### 4.1 Differences to the RestClient twin

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

A cancel of the response `Mono` *after* the response was delivered (a host operator such as `next()`
between this filter and the client) is ignored: from then on the body owns the exchange. Dashboards must
treat `adapter_outcome` as the authoritative disposition and not assume the status field is always
present. A connector that completes **without** a response at all — a host filter swallowing an error
into `Mono.empty()` — is a `failure` at ERROR with the cause WebClient raises for the caller ("completed
without emitting a response"). Pinned by the filter's unit tests: Spring's skip, a `take`, an
out-of-band cancel, a `next()`, an empty completion.

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
attempt is a crossing and gets its own line, with the same `adapter_request_id` under a trace (or, on a
traceless call, the correlation header the first attempt added to the request the retry re-sends).

### 4.8 Tracing makes every call traced

With Micrometer Tracing configured, the client observation roots a trace whenever none is active on the
subscribing context, so **every** outbound call carries a `traceparent` — sampled or not. Consequences: the
module never generates a correlation id in such a host, `correlation.id{source=generated}` reads zero by
construction, and the peer never receives an `X-Correlation-Id` from this module. Pinned by the tracing
integration test.

### 4.9 One metrics instance per registry

Micrometer deduplicates meters by id. A second `ClientLoggingMetrics` instance against the same registry
would share the **counters** but not the **gauge**: the second gauge registration is silently ignored.
Every filter therefore obtains its metrics owner through a per-registry cache, so several filters on one
registry share one owner and the gauge reports the total across them.

### 4.10 Masking is a fingerprint, not a secret

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

### 4.11 Shared code: legatium-common, inlined by Shade

The byte-identical part of the twins' shared layer lives in the `legatium-common` module
([ADR-0003](../../docs/adr/ADR-0003-legatium-common-inlined-by-shade.md)): the `Traceparent` parser (with
its tests and fuzz target), `HeaderLogProperties` (selection and masking fingerprint, with unit test and
fuzz target), the `ClientLogField` enum with its builder extensions and the `ClientLoggingProperties`
binding, `Timeouts`, `NanoTimeSource`, `CorrelationIdGenerator`, `CorrelationHeader`,
`reportQuietly`/`failOpen`, the MDC keys and scope, `BodyReadState`/`decodeTruncated` - and, since the
amendment of 2026-09-04, the metrics owner `ClientLoggingMetrics` (parameterised by the `ClientStack`:
outcome vocabulary and `client` tag) and the activation `ClientActivation`, whose twin copies had
converged to near-identity. The Maven Shade plugin inlines those classes into THIS jar at package time,
the dependency-reduced POM drops the dependency, and `legatium-common` is never published — consumers
keep adding exactly one artifact, and the shared classes stay `internal` (`-Xfriend-paths`; build from
the reactor root or with `-am`).

Everything whose twin copies genuinely differ stays deliberately duplicated: the emitters and exchanges,
filter vs. interceptor, and `BoundedBodyCapture` (two different concurrency designs).
ADR-0003 names the threshold: a twin-paired file that reaches 90 % line similarity after neutralising the
stack names is byte-identical enough to move, parameterised where it must differ. For the remainder the
accepted cost is unchanged: a change is a conscious port in both directions, and the lockstep tests catch
*named* contract drift (keys, field names, meter names, message text), not behavioural drift inside
near-identical code.

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
    │   ├── ClientRequestLoggingFilter.kt          the filter: activation, wiring, signal mapping, response mutation, complete
    │   ├── Exchange.kt                            per-exchange state, ExchangeState
    │   ├── ExchangeLogEmitter.kt                  arrival line and completion event
    │   ├── CapturingDecorators.kt                 tee(), the request decorator, the inserter wrap
    │   ├── ObservedBody.kt                 the response body operator: tee, read state, terminal signal, consumption vs. cancel
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
| Connector suites (`ConnectorContract` run as `ReactorNettyConnectorIntegrationTest`, `JdkHttpClientConnectorIntegrationTest`, `JettyConnectorIntegrationTest`, `HttpComponentsConnectorIntegrationTest`) | the connector-agnosticism contract against every connector Spring ships: the body tees on the engine's own buffers and the wire correlation header, the engine's real response and connect timeout types classified as `timeout` (the connect timeout provoked by a loopback tarpit, `Tarpit`), a refused connection as the `failure` control |
| `ClientRequestLoggingTracingIntegrationTest` | ADR-0002 beside a real Brave bridge: the injected `traceparent`, the log-to-trace join, no correlation header on traced calls, every call traced |
| Lockstep/contract tests (`TwinContractTest`, `UriTemplateAttributeTest`) | pin the twin contracts and the mirrored `WebClient` attribute; the field/template and configuration/reference lockstep (`ClientLogFieldTest`, `ClientLoggingReferenceConfigTest`, `ClientLoggingPropertiesTest`) lives once in legatium-common |

Fuzzing of the shared `Traceparent` parser and header masking lives in legatium-common; the bounded
capture's fuzz target lives in the RestClient twin (the reactive capture adds a lock and a freeze around
the same arithmetic).

### 5.2 Related documents

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
