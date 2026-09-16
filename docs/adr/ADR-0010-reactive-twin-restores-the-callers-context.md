# ADR-0010: The reactive twin restores the caller's context from the Reactor Context around its emission

**Status:** Accepted  
**Date:** 2026-09-16  
**Deciders:** Dirk Haase (maintainer)  
**Related:** ADR-0002 (the trace keys stay owned by the emission
scope; a bridge id the accessors restore never outranks the header's),
ADR-0003 (this is reactive-only code and therefore lives in the
WebClient module, not in `legatium-common`), ADR-0007 (the
`endpoint_*` keys this decision joins to are limesium's)

## Context

Both twins emit the exchange line under an additive `MdcScope`: the
module's own `adapter_*` keys and the trace keys go in, everything
else the emitting thread carries stays visible beside them, and that
is how a client line joins the server line it was made from -
limesium's `endpoint_request_id` sits on the same document without
either library knowing about the other.

On the blocking stack that join costs nothing: the interceptor runs
on the caller's thread, the wire call blocks it, and the caller's MDC
is simply present when the response is closed. On the reactive stack
it does not hold. The thread that completes the body - the emission
point - is an event-loop thread that never ran the inbound request
and carries none of its MDC. The reactive guide said so and left the
join to the host: with `spring.reactor.context-propagation=auto`
Reactor restores thread-locals around every operator, and the module's
terminal callback inherits them. Under Boot's default `limited`, which
restores around `tap` and `handle` only, the client line of a reactive
host carries no server identity at all.

Two ways to close the gap were weighed:

1. **A thread-local snapshot at wiring**, the pattern of the
   `ExchangeDiary` in the host's own tool box: `MDC.getCopyOfContextMap()`
   on the subscribing thread, re-installed around the emission.
   It has three defects on this stack. The subscribing thread of a
   reactive host is often an event-loop thread itself, whose MDC is
   empty or - worse - the leftover of a foreign request, so the
   snapshot produces a *false* join. A snapshot is stale by
   construction and would override a legitimately updated value on a
   same-thread emission. And a retry resubscribes on the retry
   scheduler, so the attempts of one call snapshot different threads.
   On top, it copies a map per call before the level gate.
2. **The Reactor Context** the caller subscribed with. It is the
   mechanism Reactor itself uses to carry a subscriber's context
   across threads: immutable, attached at subscription, identical for
   every operator, every thread and every resubscription of the
   chain. Limesium's reactive twin writes its `endpoint_*` identity
   there under the MDC key names and registers a
   `ThreadLocalAccessor` per key with Micrometer's context propagation
   - precisely so that a consumer can turn the context back into
   thread-locals.

## Decision

We adopt option 2: **the WebClient twin captures the subscriber's
`ContextView` at wiring and restores the caller's thread-locals from
it around each of its emissions.**

### Capture

The filter wraps its work in `Mono.deferContextual` instead of
`Mono.defer` and keeps the `ContextView` on the `Exchange`. No copy is
made: the context is immutable, and the reference is the same for the
arrival line, the completion event and every retry attempt.

### Restoration

`AmbientContextRestorer` (WebClient module) turns the context back
into thread-locals for the duration of the single log statement,
through Micrometer's `ContextSnapshotFactory.setThreadLocalsFrom(ctx)`,
and restores every touched value when the scope closes. The module
interprets nothing itself: which context entries become which
thread-locals is decided by the `ThreadLocalAccessor`s the host - or
limesium - registered. The order is fixed: the ambient scope opens
first, the module's own `MdcScope` inside it, so the trace keys stay
owned by the module (ADR-0002) and a bridge id an accessor restored
never reaches the event.

```kotlin
restoreAmbientQuietly(exchange).use {
    MdcScope(exchange.requestId, exchange.method, exchange.target, exchange.traceId, exchange.spanId, ownsTraceKeys = true).use {
        logEvent(...)
    }
}
```

### Additive, like everything else the module does to the MDC

Micrometer's `clearMissing` stays **off**. Only values the context
holds are installed; a thread-local the context does not mention is
left as the emitting thread has it. On a call that completes
synchronously the emitting thread *is* the caller's, and its inbound
identity or observation scope must not be reset by a restorer that
only knows what the context holds. The trade-off is stated in
[Consequences](#consequences).

### Opt-in by presence, not by property

`io.micrometer:context-propagation` is an **optional** dependency of
the WebClient module. When it is on the host's classpath the
restorer is the context-propagation one; otherwise it is a no-op and
the module behaves exactly as before this decision. No
`adapter-logging.*` key exists for it: the configuration stays
identical between the twins, and the library presence is the same
opt-in limesium uses for the mirror-image feature. Unlike limesium,
no startup warning about the propagation mode is needed: the module
restores around its own emission and does not depend on `auto`.

### Fail-open

A restorer that throws - a host accessor failing on this thread -
costs the ambient keys, counted as `stage=wiring` on the fail-open
meter, never the event, which then carries the module's own identity
alone.

### A deliberate stack difference

The RestClient twin gets no counterpart. The blocking call carries
the caller's context through the wire call on the thread itself; a
thread-local snapshot there would only serve the rare host that
closes a response on another thread, at the cost of a map copy on
every call. This joins `cancelled` and the emission point in the list
of documented stack differences.

## Consequences

**Positive:**

- The client line joins the server line on the reactive stack under
  Boot's default propagation mode, on whichever thread completes the
  body, and identically across retry attempts.
- The source of truth is the one Reactor itself uses; there is no
  thread to be stale or foreign, and no map is copied.
- The host's accessors decide what is restored; the module carries
  no list of key names of other libraries.
- Nothing changes for a host without the optional library, and
  nothing changes in the configuration namespace.

**Negative:**

- The join needs two things from the host: the identity in the
  Reactor Context and an accessor for it. Limesium provides both for
  its keys; a host's own MDC key needs the same two steps, or it stays
  invisible on the event-loop thread.
- With `clearMissing` off, a value that a broken host left on an
  event-loop thread without a scope is not cleared for the emission.
  The module defends against its own mistakes, not against a host
  that sets MDC outside a scope.
- One optional dependency more in the published POM, and the twins
  now differ in a mechanism, not only in a vocabulary - the guides
  carry the asymmetry.

**Neutral:**

- Under `spring.reactor.context-propagation=auto` the restoration is
  redundant: Reactor already restored the same values around the
  operator, and the nested scope installs the same values again.
- A coroutine caller with `MDCContext` is not covered: `MDCContext`
  is a coroutine element, not a Reactor Context entry. Such a host
  gets the join only when its identity is in the Reactor Context as
  well.
