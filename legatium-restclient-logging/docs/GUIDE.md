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
   1. [Automatic wiring](#31-automatic-wiring)
   2. [Manual wiring](#32-manual-wiring)
   3. [Interceptor order and other interceptors](#33-interceptor-order-and-other-interceptors)
   4. [Verifying the integration](#34-verifying-the-integration)
4. [Special characteristics](#4-special-characteristics)
   1. [Differences to the WebClient twin](#41-differences-to-the-webclient-twin)
   2. [Duration is response occupancy](#42-duration-is-response-occupancy)
   3. [A response that is never closed](#43-a-response-that-is-never-closed)
   4. [Failures while reading the body](#44-failures-while-reading-the-body)
   5. [Timeouts and how they are recognised](#45-timeouts-and-how-they-are-recognised)
   6. [RestTemplate has no URI template](#46-resttemplate-has-no-uri-template)
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
  ([Legatium guide §7.4](../../docs/GUIDE.md#74-meters)).
- **No retries, no circuit breaking, no request rewriting.** The module observes; the one thing it adds to
  a request is the correlation header on a traceless call without one ([Legatium guide §7.6](../../docs/GUIDE.md#76-trace-correlation)).
- **No body masking transformers and no per-key response sampling.** Bodies are logged verbatim up to the
  capture limit, and the logger level is the only volume control ([Legatium guide §6.5](../../docs/GUIDE.md#65-logger-levels)).
- **No replaying body cache.** The response tee is passive; an unread response body is logged as absent.
- **No exporting of a `MeterRegistry`.** The host's registry is consumed if present; otherwise the
  meters are no-ops (an empty `CompositeMeterRegistry`).
- **No clients built by hand.** The customizers cover every client built through Boot's builders (and the
  HTTP service client groups built from them); a hand-built `RestClient` gets the interceptor bean added
  by the host ([§3.2](#32-manual-wiring)).

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
  "spanId": "00f067aa0ba902b7",
  "endpoint_request_id": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

The `adapter_request_id` / `adapter_method` / `adapter_route` / `traceId` / `spanId` entries come from the
MDC ([Legatium guide §7.2](../../docs/GUIDE.md#72-mdc-keys)); the `adapter_*` key-values are the field family of [Legatium guide §7.1](../../docs/GUIDE.md#71-log-fields). The
`endpoint_request_id` in the example is not this module's: it is the ambient MDC of the inbound request
the call was made from (Limesium), which the additive emission scope leaves in place — this is how the
client line joins the server line without any coupling between the two libraries. How MDC entries land
in the document (flat, nested, renamed) is the encoder's decision.

With the optional arrival line enabled, a second, earlier line precedes it:

```
Adapter http exchange started POST https://api.example.com/things/42 [adapter_request_id=4bf92f…]
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
| Message text, this stack's outcome vocabulary | this module's emitter and `ClientStack` | `TwinContractTest` in both modules; the meter names, MDC keys and shared literals once in `SharedContractTest` (`legatium-common`) |

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
| `CapturingClientHttpResponse` | The response the client gets back: delegates, tees the body the application reads, reports a failure of **any** delegate operation (status, headers, body open/read/close, response close), and turns `close()` into the emission point. |
| `Exchange` | Per-exchange state from entry to emission; the exactly-once guards. |
| `ExchangeLogEmitter` | Builds and emits the arrival line and the completion event; resolves level, outcome and cause (timeouts via the shared `Timeouts`); records body sizes; opens the emission `MdcScope` with trace ownership. |
| `ClientLogField` | The wire names and the exact JVM type of each structured field; a wrongly typed value drops the field with a warning, never the event. Shared (legatium-common): one enum for both twins. |
| `ClientLoggingMetrics` (shared, `legatium-common`) | The six meters, one implementation for both twins parameterised by the `ClientStack` (outcome vocabulary, `client` tag) - the fixed-tag meters pre-registered, the body meters created lazily per tag, per-meter fallback to a private registry on registration conflict. |
| `ClientActivation` (shared, `legatium-common`) | Which calls are logged at all: host exclusion, include patterns, exclude prefixes - one implementation, so the semantics are identical on both stacks by construction. |
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
| `HeaderValueMasker` | `@ConditionalOnMissingBean` | `HeaderValueMasker.forKey(properties.maskingKey)` — the `length:hash` fingerprint, HMAC-keyed when `masking-key` is set; the one bean both twins mask with (the interceptor's constructor defaults to the same, so manual wiring honours the key too) |
| `ClientRequestLoggingInterceptor` | `@ConditionalOnMissingBean` | the interceptor, built from the bound properties and the host's `MeterRegistry` (`ObjectProvider`; an empty, no-op `CompositeMeterRegistry` without one) |
| `RestClientCustomizer` | `@ConditionalOnClass(RestClientCustomizer)`, `@Order(LOWEST_PRECEDENCE - 10)` | `builder.requestInterceptor(interceptor)` on every `RestClient.Builder` Boot hands out |
| `RestTemplateCustomizer` | `@ConditionalOnClass(RestTemplateCustomizer)`, same order | appends the interceptor to every `RestTemplate` built through `RestTemplateBuilder` |

Because the interceptor is its own bean, a host can replace it while keeping the customizers
([Legatium guide §3](../../docs/GUIDE.md#3-overriding-beans)). Boot's `spring-boot-restclient` module is an **optional** dependency:
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
trace ownership, see [Legatium guide §7.6](../../docs/GUIDE.md#76-trace-correlation)), selects the response headers, decodes the captured
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

The captures exist only when a body is logged (in any mode — `on-failure` needs the bytes before the outcome is known, [Legatium guide §6.3](../../docs/GUIDE.md#63-body-logging-and-body-measuring)) **or** measured; without either, the response wrapper still
exists (the close hook is the emission point), but the body stream passes through with the read-failure
guard only.

**The capture mirrors consumption, not transmission.** The log shows exactly the bytes the application
actually read — no more. A response body the application never opens (`toBodilessEntity()`, a
`ResponseEntity<Void>`) is logged as absent and records no size sample, even though the peer sent one; a
body read only partially is captured to exactly that extent, and the `[truncated, N bytes total]` note
counts what flowed, not `Content-Length`. This is the deliberate trade-off against a replaying buffer —
the log tells the truth about what the application processed, and streaming stays untouched. Because of
that, the log cannot tell a body the peer sent but the application dropped from one that was never sent;
the counter `adapter.response.body.read` ([Legatium guide §7.4](../../docs/GUIDE.md#74-meters)) exists for exactly that distinction.

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
its own bookkeeping: a wire call that dies with an `Error` (an inner interceptor's `AssertionError`, a
`LinkageError` in the engine on first use) still closes the open-exchange gauge (`abandonExchange`, no
emission attempted, one WARN breadcrumb), so the liveness signal cannot drift over something the module
never caused. An `Error` thrown by the logging backend *during* the emission at response close is outside
the promise and reaches the client's `finally`.

### 2.8 Injectable collaborators

Time and randomness are injected, not ambient:

- `NanoTimeSource` — monotonic nanoseconds for `adapter_duration_ms` and the slow threshold; the single
  production read of `System.nanoTime()` is `NanoTimeSource.SYSTEM`. Log timestamps come from the
  logging backend, keeping the two time domains separate.
- `CorrelationIdGenerator` — the id for traceless calls without a correlation header; `DEFAULT` (the
  counting generator, ADR-0004) by default. Never consulted for a traced call (ADR-0002: the
  `traceparent` trace id is the request id) — and in a host with tracing configured, never at all
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
once, in the [Legatium guide](../../docs/GUIDE.md). This chapter holds what is specific to the interceptor:
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
  ([§4.9](#49-one-metrics-instance-per-registry)). A second instance would not break anything, but it
  buys nothing.
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
  ([Legatium guide §6.4](../../docs/GUIDE.md#64-activation-hosts-and-paths)), so a manually attached interceptor applies the same rules as
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
the interceptors of earlier customizers and of the builder's own configuration, and runs **inside** them —
closest to the wire:

- an authentication interceptor outside it has already added its header, so the logged (and masked)
  request headers are what the peer receives;
- a retrying interceptor outside it invokes it once per attempt — one line per attempt, each an honest
  crossing ([§4.7](#47-retries-yield-one-line-per-attempt));
- interceptors a host adds **after** the customizers ran (directly on a builder it obtained from Boot)
  run inside this one and are outside that guarantee — they see the request after this interceptor did.

The `traceparent` header is not affected by the order at all: the client observation Boot registers
injects it into the request **before** any interceptor runs ([Legatium guide §7.6](../../docs/GUIDE.md#76-trace-correlation)).

Activation is evaluated **in the interceptor** (`shouldNotFilter`), so its semantics are byte-identical
with the WebClient twin. If the host needs a different position, it attaches the bean itself
([§3.2](#32-manual-wiring)).

### 3.4 Verifying the integration

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
module does not reconstruct templates by guessing.

### 4.7 Retries yield one line per attempt

The interceptor sits innermost ([§3.3](#33-interceptor-order-and-other-interceptors)), so a retrying
interceptor (or a resilience decorator around the client) invokes it once per attempt. Each attempt is a
crossing and gets its own line — with the same `adapter_request_id` under a trace, or a **new** generated
id per attempt on a traceless call (each attempt wires afresh and the retried request already carries the
first attempt's correlation header only if the retrying layer reuses the mutated request object; a rebuilt
request gets a new id). Dashboards counting calls per peer count attempts; `http.client.requests` does
the same.

### 4.8 Tracing makes every call traced

With Micrometer Tracing configured, the client observation roots a trace whenever none is active, so
**every** outbound call carries a `traceparent` — sampled or not (an unsampled trace still propagates,
with flags `00`). Consequences: the module never generates a correlation id in such a host,
`correlation.id{source=generated}` reads zero by construction, and the peer never receives an
`X-Correlation-Id` from this module. A peer without tracing that needs a quotable id in that setup is a
matter for the host's propagation configuration (baggage), not for this module — which stays neutral.
Pinned by the tracing integration test.

### 4.9 One metrics instance per registry

Micrometer deduplicates meters by id. A second `ClientLoggingMetrics` instance against the same registry
would share the **counters** (increments merge) but not the **gauge**: the second gauge registration is
silently ignored. Every interceptor therefore obtains its metrics owner through a per-registry cache, so
several interceptors on one registry (a host wiring extra instances by hand) share one owner and the
gauge reports the total across them.

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
interceptor vs. filter, and `BoundedBodyCapture` (two different concurrency designs).
ADR-0003 names the threshold: a twin-paired file that reaches 90 % line similarity after neutralising the
stack names is byte-identical enough to move, parameterised where it must differ. For the remainder the
accepted cost is unchanged: a change is a conscious port in both directions, and the lockstep tests catch
*named* contract drift (keys, field names, meter names, message text), not behavioural drift inside
near-identical code.

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
| Lockstep/contract tests (`TwinContractTest`, `UriTemplateAttributeTest`) | pin the message text, this stack's outcome vocabulary and the mirrored `RestClient` attribute; the shared literals (`SharedContractTest`), the field/template and configuration/reference lockstep (`ClientLogFieldTest`, `ClientLoggingReferenceConfigTest`, `ClientLoggingPropertiesTest`) and the metrics owner's registration behaviour (`ClientLoggingMetricsTest`) live once in legatium-common; the shaded jar itself is exercised by the standalone `consumer-smoke/` build |

Fuzzing of the shared `Traceparent` parser and header masking lives in legatium-common; the bounded
capture's fuzz target lives here.

### 5.2 Related documents

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
