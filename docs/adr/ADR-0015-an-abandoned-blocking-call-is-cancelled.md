# ADR-0015: A blocking call its caller abandoned is `cancelled`; the interrupt wins over a timeout

**Status:** Accepted  
**Date:** 2026-10-21  
**Deciders:** Dirk Haase (maintainer)  
**Related:** ADR-0012 (the outcome vocabulary and the classification
order this ADR extends), ADR-0006 (the body gate, which treats
`cancelled` as not `success` on both twins), ADR-0008 (the `outcome`
tag of `adapter.logging.events` gains `cancelled` on the blocking
stack), ADR-0003 (the vocabulary is shared; the recognition stays in
the blocking twin, its only user)

## Context

The reactive twin has logged `cancelled` at WARN since its first
release: a subscription the caller abandoned (a downstream
`timeout()` operator, a disposed caller) says nothing about the
peer. Its documentation called this "the reactive reality a blocking
call cannot have".

A blocking call has the same reality. Two situations produce it, both
normal operation and both frequent under load (issue #88, finding 16
of veredium's defect analysis of 2026-10-21):

1. **The caller's budget expires.** A Resilience4j `TimeLimiter` runs
   the call on a worker while the caller waits on a `Future`; when the
   budget expires it cancels the future with `cancel(true)`, which
   interrupts the worker.
2. **The caller is interrupted.** Its own deadline expired, its scope
   was cancelled, or a `CompletableFuture` cancelled through to the
   worker.

The engines react in their own way, which the connector suites now
pin per engine:

- On a **virtual** thread the JDK makes socket I/O interruptible: it
  closes the socket and the read fails with
  `SocketException: Closed by interrupt` (`HttpURLConnection`,
  Apache's classic client). Apache then even retries the request, and
  the retry's pool lease throws a raw `CancellationException`.
- The JDK client, Jetty and Reactor Netty wait on a future; they wake
  up on an interrupt on **any** thread. Spring's JDK and Jetty request
  factories restore the flag and wrap the `InterruptedException` in
  an `IOException`.
- On a **platform** thread a socket read is not interruptible: the
  call finishes with the peer's answer, the flag still set.
- Apache's pool **lease** wait is a future too: interrupted, the
  client cancels the lease, restores the flag and throws
  `RequestFailedException("Request aborted")`.

The blocking twin classified all of these as ERROR `failure`: the
generic branch for a thrown call. ERROR is the level alerting fires
on, so a wave of upstream deadlines filled it with lines whose only
message was "the caller left", and an alert keyed on
`adapter_outcome == "cancelled"` never fired for the blocking stack.

## Decision

**A blocking call that FAILED while its caller had abandoned it is
`cancelled` at WARN, without a cause on the event, exactly as the
reactive twin logs an abandoned subscription. Both stacks share the
whole outcome vocabulary.**

### Recognition

The interceptor sees the exception the wire call or the body read
threw. It records it together with the interrupt status of the
thread it surfaced on (`Exchange.recordFailure`), and the emitter
classifies in this order:

| Condition                                                             | Level   | `adapter_outcome` |
|-----------------------------------------------------------------------|---------|-------------------|
| the call threw on an interrupted thread                               | `WARN`  | `cancelled`       |
| the call threw with an interrupt in its cause chain (`Cancellations`) | `WARN`  | `cancelled`       |
| the call threw and a timeout is in the cause chain                    | `WARN`  | `timeout`         |
| the call threw                                                        | `ERROR` | `failure`         |
| the call answered                                                     | by status, ADR-0012 | by status |

1. **The interrupt flag is the primary signal**, the only one that
   does not depend on an exception type. It is read with
   `isInterrupted()`, never `Thread.interrupted()`: the interrupt
   belongs to the caller, and the interceptor leaves it exactly as it
   found it. It is read where the failure surfaces, not at the
   emission: a host that closes the response on another thread
   (ADR-0011) must not lose the caller's departure, and a later
   failure on a thread that is not interrupted does not undo it.
2. **The cause chain is the fallback** for a flag something between
   the engine and the interceptor already consumed. It walks causes
   and suppressed exceptions like `Timeouts` and matches by class
   name, so the twin gains no dependency:
   `java.lang.InterruptedException` and
   `java.nio.channels.ClosedByInterruptException` as types; the JDK's
   `SocketException("Closed by interrupt")` and Apache's
   `RequestFailedException("Request aborted")` by message as well,
   because their classes also carry other failures. A message is no
   contract; a reworded one costs only the fallback, never the flag.

### The interrupt wins over a timeout

`SocketTimeoutException` and Apache's
`ConnectionRequestTimeoutException` are `InterruptedIOException`s,
like the exceptions an interrupt produces. The rule is therefore not
"an `InterruptedIOException` is a cancellation": only the flag and the
explicit types above count. When both are present, the cancellation
wins: the caller left, and whatever else the transport reported in
the same moment is secondary. An alert on `timeout` keeps counting
slow peers, not impatient callers.

### An interrupt without a failure is no cancellation

When the call answered and was closed without an exception, it is
classified by its status, even if the thread is interrupted by then.
This is the platform-thread case of an engine that blocks in a socket
read: the answer arrived before anybody stopped waiting for it, and
the line reports the exchange, not the caller's mood. The reactive
twin does the same for a body that completed before the cancel.

**Not chosen:** `java.util.concurrent.CancellationException` as a
cause-chain type. Apache throws it for its retry's lease after an
interrupt, which the flag already covers, but also when a pool is
shut down under a waiting lease, which is no caller's decision.

**Verification.** `CancellationsTest` pins the fallback and its
controls (timeouts, a plain `InterruptedIOException`, Apache's
`Request execution failed`). `ClientRequestLoggingInterceptorCancellationTest`
pins the order, the flag before and after the emission, the body read
interrupted and closed on another thread, the answered call on an
interrupted thread and the `on-failure` body gate.
`RequestFactoryContract` pins every engine Spring ships on a virtual
thread, on a platform thread and through `Future.cancel(true)`; the
HttpComponents suite the interrupted pool lease. Both twins'
`TwinContractTest` pin the same line for "the caller abandoned the
exchange" and the same five pre-registered outcomes.

## Consequences

**Positive:**

- ERROR means a failed call again; a TimeLimiter in front of a slow
  backend no longer floods it.
- One disposition for one situation on both twins: an alert on
  `cancelled` covers both stacks, and the blocking twin's
  `adapter.logging.events{outcome="cancelled"}` is pre-registered at
  zero like the reactive one's.
- `ClientStack` loses its per-stack outcome list: the vocabulary is
  one contract, which removes the vocabulary check in
  `ClientLoggingMetrics.eventEmitted`.

**Negative:**

- **Log and meter contract change:** an alert on `failure` for the
  blocking stack sees fewer events, one on `cancelled` now fires for
  it. Recorded in the CHANGELOG.
- A call interrupted for a reason that is not an abandonment (a host
  that interrupts its own pool threads on shutdown) logs `cancelled`
  as well. From the exchange's point of view nobody waited for it
  any more, which is what the value says.
- A cancellation carries no stack trace. The WARN breadcrumb on the
  module's own logger still names the exception the call threw.

**Neutral:**

- What an engine throws on an interrupt is not part of legatium's
  contract, and the classification never depends on it. It is pinned
  anyway, in scenarios of their own, because the cancellation page
  documents it for operators (History, 2026-10-23).

## History

- **2026-10-21:** first cut.
- **2026-10-23:** the exceptions an engine throws for an abandoned
  call - at the interceptor and at the `RestClient` caller - are
  pinned per engine (`RequestFactoryContract.abandonedCallExceptions`,
  the `should throw what the cancellation page documents …` scenarios,
  and the interrupted pool lease of the HttpComponents suite), so that
  `docs/cancellation-by-engine.md` cannot drift from an engine
  upgrade unnoticed. They pin the page, not the contract; CONTRIBUTING
  records the exception to its engine-exception rule.
