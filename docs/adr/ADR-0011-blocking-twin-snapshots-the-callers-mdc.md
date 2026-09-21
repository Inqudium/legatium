# ADR-0011: The blocking twin snapshots the caller's MDC for a response closed on another thread

**Status:** Accepted  
**Date:** 2026-09-16  
**Deciders:** Dirk Haase (maintainer)  
**Related:** ADR-0010 (the reactive twin's counterpart, with the
Reactor Context as the source; its "deliberate stack difference"
section is narrowed by this ADR), ADR-0002 (the trace keys are left
out of the snapshot and stay owned by the emission scope), ADR-0003
(reactive and blocking restorers are stack-specific and live in
their modules)

## Context

The RestClient twin logs the exchange line when the response is
**closed**. Almost always that is the caller's thread: the interceptor
runs on it, the wire call blocks it, the client's converters read the
body on it and close the response on it, and the caller's MDC - an
inbound request's `endpoint_*` identity from limesium, for one - is
simply present. ADR-0010 recorded that as the reason the blocking
twin needed no counterpart to the reactive twin's context
restoration.

One gap remains. A host that takes the response as a stream and hands
it to another thread - a download piped into a pooled writer, a body
consumed by an executor - closes it there, and that thread carries
none of the caller's MDC. The client line then joins nothing, or,
when the thread is a pooled one that served another request, joins
the wrong server line. Rare, but the one case in which the blocking
twin's promise "the client line joins the server line" is silently
broken.

A thread-local snapshot was rejected for the *reactive* twin
(ADR-0010) because there the subscribing thread is often not the
caller's and its MDC is the wrong source. On the blocking stack the
opposite holds: the thread that wires the exchange **is** the caller's
thread, and its MDC is the only source there is.

## Decision

We adopt a **thread-local snapshot of the caller's MDC, taken at
wiring and applied only when the response is closed on another
thread.**

### Capture

`CallerMdcSnapshot.capture()` runs in the interceptor's wiring, on the
calling thread: `MDC.getCopyOfContextMap()`, minus the keys the module
owns (`adapter_request_id`, `adapter_method`, `adapter_route`) and the
trace keys (`traceId`, `spanId`). Those belong to the emission's
`MdcScope`: a nested client call must not carry the outer call's
identity, and a bridge's stale id must not reach an event through a
back door the snapshot would open. The snapshot remembers the
capturing thread. An empty MDC yields the shared `NONE` instance - the
adapter returns null and no map is built.

### Restoration

The emitter opens the snapshot's scope around the exchange line,
outside the module's own `MdcScope` - the same layering as the
reactive twin. The scope is a **no-op on the capturing thread**: the
caller's MDC is present there and a value the caller updated between
the call and the close stays the newer one. On another thread the
snapshot's entries are installed for the duration of the log
statement, the previous values remembered and restored on close.
Additive, like everything else the module does to the MDC: the
snapshot wins for the keys it holds, a key only the emitting thread
has stays visible, and nothing is cleared.

```kotlin
val callerScope = restoreCallerMdcQuietly(exchange)   // no-op on the caller's thread
try {
    MdcScope(exchange.requestId, exchange.method, exchange.target, exchange.traceId, exchange.spanId, ownsTraceKeys = true).use {
        logEvent(...)
    }
} finally {
    restoreQuietly(callerScope, exchange)
}
```

### Cost, and why there is no switch

One map copy per call, proportional to the size of the caller's MDC,
before the level gate - the snapshot must exist before the interceptor
returns, and whether the close will happen elsewhere is unknown at
that point. A host without MDC pays nothing. No `adapter-logging.*`
key switches it off: the configuration stays identical between the
twins, the reactive twin needs no switch for its mechanism, and a
copy of a handful of entries is not worth a knob.

### Fail-open

A throwing MDC adapter at capture costs the snapshot, counted as
`stage=wiring`; the call is wired and logged with the module's own
identity. An adapter that throws while the snapshot is installed at
emission costs the caller's keys, counted the same way, never the
event; one that throws while the snapshot's scope closes afterwards
is counted the same way and the line is already out. Both paths are
driven in the tests through a failing adapter swapped in at the
SLF4J boundary, not through a seam of their own.

## Consequences

**Positive:**

- The client line joins the server line also when the host closes
  the response on another thread, and the blocking twin's promise
  holds without a documented exception.
- Both emitters now share one layering - the caller's context
  outside, the module's own scope inside - with a stack-specific
  source each: the Reactor Context on the reactive stack, the
  calling thread on the blocking one.
- On the caller's thread nothing changes: the snapshot is not
  applied, so the normal path keeps its live MDC and its cost is the
  copy alone.

**Negative:**

- A map copy on every call, whether or not it is ever used. Small, but
  paid on the hot path of every blocking client.
- The snapshot is a copy of the past. On another thread that is the
  right thing; a host that deliberately changes the caller's MDC
  after the call and closes elsewhere sees the value from call time.
- A pooled thread that served the caller, was reused for another
  request and *then* closes the old response is "the capturing
  thread" - the snapshot does not apply and the event shows that
  thread's current, foreign MDC. This is exactly the behaviour before
  this ADR; nothing regresses, and the case is not addressed.

**Neutral:**

- ADR-0010's statement that the RestClient twin gets no counterpart
  is narrowed to the mechanism: it gets no *Reactor Context*
  restoration, because a blocking call has no Reactor Context. The
  layering is now the same on both stacks.
