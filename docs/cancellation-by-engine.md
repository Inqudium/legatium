# Cancellation by engine (RestClient / RestTemplate)

How each HTTP client implementation behind a `RestClient` or `RestTemplate` reacts when the caller of
a call stops waiting, and what legatium logs for it. Read this before you put a `TimeLimiter`, a
deadline or a cancellable future in front of a blocking client: the engine decides whether the
cancellation takes effect, what the caller gets thrown, and whether a thread stays tied up.

**Scope: the blocking twin only** (`legatium-restclient-logging`). Both blocking clients go through
the same `ClientHttpRequestInterceptor` and the same `ClientHttpRequestFactory` engines, so everything
here applies to `RestClient` and `RestTemplate` alike. The reactive twin (`legatium-webclient-logging`)
is not affected: a `WebClient` call blocks no thread and is cancelled by cancelling its subscription,
the same signal on every connector. How it logs that is in the
[WebClient guide §4.2](https://github.com/Inqudium/legatium/blob/main/legatium-webclient-logging/docs/GUIDE.md#42-cancellation-and-the-missing-status);
what each reactive connector does with its connection on a cancel is not covered by the suites.

The classification rule itself is
[ADR-0015](adr/ADR-0015-an-abandoned-blocking-call-is-cancelled.md). The short version is in the
[RestClient guide §4.8](https://github.com/Inqudium/legatium/blob/main/legatium-restclient-logging/docs/GUIDE.md#48-a-call-its-caller-abandoned-is-cancelled).
Everything on this page about the engines Spring ships is pinned by the connector suites
(`RequestFactoryContract` and its five subclasses, `HttpComponentsRequestFactoryIntegrationTest`).
Apache HttpClient 4 (§2.3) was observed once, on 2026-10-21, through a temporary adapter; nothing
re-checks it. It
was observed on JDK 26 with Spring Boot 4.1.1 (Spring Framework 7.0.9), Apache HttpClient 5.6.4,
Apache HttpClient 4.5.14, Jetty 12.1.12 and Reactor Netty 1.3.7.

---

## 1. How a caller abandons a blocking call

A blocking call has only one cancellation mechanism: **the interrupt of the thread that runs it**.
Every higher-level construct comes down to it:

| What the application does | What reaches the thread running the call |
|---|---|
| Resilience4j `TimeLimiter` whose budget expires (`cancelRunningFuture = true`, the default) | `Future.cancel(true)` on the worker, so the worker is interrupted |
| `Future.cancel(true)` / `ExecutorService.shutdownNow()` | interrupt of the worker |
| `CompletableFuture.cancel(...)` | **nothing**: a `CompletableFuture` does not interrupt the thread that computes it, and the call runs to its end |
| a structured-concurrency scope that is shut down, a deadline that interrupts the caller | interrupt of the thread in the scope |
| `Thread.interrupt()` directly | interrupt |

A `CompletableFuture` cancelled on its own therefore leaves the call running: the line it logs is the
call's real outcome, usually `success`. Only an interrupt reaches the engine.

## 2. What each engine does with the interrupt

The column that matters is the **thread kind**. Since JDK 21, socket I/O is interruptible on a
**virtual** thread: the JDK closes the socket and the blocked read fails with
`SocketException: Closed by interrupt`. On a **platform** thread a blocked socket read ignores the
interrupt; only a wait on a future or a lock wakes up.

### 2.1 Overview

| Engine (Spring request factory) | Waits on | Virtual thread | Platform thread |
|---|---|---|---|
| JDK `HttpClient` (`JdkClientHttpRequestFactory`) | a future | `cancelled` at once | `cancelled` at once |
| Jetty (`JettyClientHttpRequestFactory`) | a future | `cancelled` at once | `cancelled` at once |
| Reactor Netty (`ReactorClientHttpRequestFactory`) | a `block()` | `cancelled` at once | `cancelled` at once |
| `HttpURLConnection` (`SimpleClientHttpRequestFactory`) | the socket read | `cancelled` at once | **ignored**: the call waits for the answer and logs by its status |
| Apache HttpClient 5 (`HttpComponentsClientHttpRequestFactory`), during the exchange | the socket read | `cancelled` at once, after an internal retry attempt | **ignored**: the call waits for the answer and logs by its status |
| Apache HttpClient 5, waiting for a pooled connection | the pool's lease future | `cancelled` at once | `cancelled` at once |
| Apache HttpClient 4 (own adapter, §2.3), during the exchange | the socket read | `cancelled` at once, after three internal retries | **ignored**: the call waits for the answer and logs by its status |
| Apache HttpClient 4, waiting for a pooled connection | the pool's lease future | `cancelled` at once | `cancelled` at once |

In every case the thread's **interrupt flag is still set** after the call returns or throws: the
engines restore it, and legatium only reads it. The caller's own interrupt handling still sees it.

### 2.2 Per engine

What the interceptor receives, what the caller gets thrown, and the line legatium writes:

| Engine | Exception at the interceptor | Thrown to the `RestClient` caller | Line |
|---|---|---|---|
| JDK `HttpClient` | `IOException("Request was interrupted: …")` over the `InterruptedException`; Spring restores the flag | `ResourceAccessException` | WARN `cancelled`, `-> -` |
| Jetty | `IOException("Request was interrupted: …")` over the `InterruptedException`; Spring restores the flag | `ResourceAccessException` | WARN `cancelled`, `-> -` |
| Reactor Netty | `IOException` over the `InterruptedException` from `block()` | `ResourceAccessException` | WARN `cancelled`, `-> -` |
| `HttpURLConnection`, virtual thread | `SocketException: Closed by interrupt` | `ResourceAccessException` | WARN `cancelled`, `-> -` |
| `HttpURLConnection`, platform thread | none: the answer arrives | none: the caller gets the body | INFO `success` with the status (or what the status says) |
| Apache 5, virtual thread | `java.util.concurrent.CancellationException` (see below) | the **raw** `CancellationException`, not wrapped by `RestClient` | WARN `cancelled`, `-> -` |
| Apache 5, platform thread | none: the answer arrives | none: the caller gets the body | INFO `success` with the status |
| Apache 5, pool lease | `RequestFailedException("Request aborted")` over the `InterruptedException`; Apache restores the flag | `ResourceAccessException` | WARN `cancelled`, `-> -` |
| Apache 4, virtual thread | `SocketException: Closed by interrupt`, after three retries (see below) | `ResourceAccessException` | WARN `cancelled`, `-> -` |
| Apache 4, platform thread | none: the answer arrives | none: the caller gets the body | INFO `success` with the status |
| Apache 4, pool lease | `RequestAbortedException("Request aborted")` over the `InterruptedException` | `ResourceAccessException` | WARN `cancelled`, `-> -` |

**Apache on a virtual thread.** The interrupt closes the socket. Apache's `HttpRequestRetryExec` sees
a `SocketException` it considers recoverable and retries the request (it logs
`recoverable I/O exception … will be automatically re-executed` at INFO). The retry's lease of a
connection from the pool then fails on the still-interrupted thread with a raw
`CancellationException`. It is a `RuntimeException`, so `RestClient` passes it through unwrapped: a
caller that catches only `ResourceAccessException` misses it. legatium still logs `cancelled`, because
the interrupt flag is set. A client built with `disableAutomaticRetries()` skips the retry step;
which exception it then throws is not covered by the suites.

**Apache 4 on a virtual thread.** The same mechanism as Apache 5, with a different end: HttpClient
4's `RetryExec` retries the `SocketException` three times (the default `DefaultHttpRequestRetryHandler`,
logged at INFO as `I/O exception (java.net.SocketException) caught … Closed by interrupt` and
`Retrying request`), each attempt fails at once on the still-interrupted thread, and the last
`SocketException` reaches the caller wrapped in a `ResourceAccessException`, no raw runtime exception.

### 2.3 Apache HttpClient 4

Spring Framework 6 dropped its request factory for Apache HttpClient 4; Spring 7's
`HttpComponentsClientHttpRequestFactory` targets HttpClient 5 only, and Boot's detection (§6.1) does not
look for HttpClient 4 at all. A host that still runs it behind `RestClient` or `RestTemplate` bridges
it with a `ClientHttpRequestFactory` of its own. The results above were observed on 2026-10-21 by
running the connector contract once through such an adapter (modelled on the one Spring 5 shipped:
the body buffered, executed in one `execute`, the entity consumed and the response closed at
`close`), against httpclient 4.5.14 / httpcore 4.4.16. The adapter was not kept, so the build does
not re-check them; they hold for an adapter of that shape, and one that interrupts, retries or wraps
differently changes them. HttpClient 4 is end-of-life, 4.5.x gets
maintenance releases only.

Its timeouts classify like HttpClient 5's: the read timeout is the JDK's `SocketTimeoutException`, the
connect timeout `org.apache.http.conn.ConnectTimeoutException`, and the pool lease timeout its subclass
`ConnectionPoolTimeoutException` - all `timeout` at WARN. The lease timeout carries no cause, so
`Timeouts` matches the `ConnectTimeoutException` family by name, as it does HttpClient 5's
`ConnectionRequestTimeoutException`. That classification IS pinned: the common module's
`TimeoutsTest` matches it against the real HttpClient 4 classes.

**The reactive twin.** Outside the scope of this page (see the introduction): a `WebClient` call is
cancelled by cancelling its subscription, the same way on every connector. The engine differences above
do not apply there; the reactive rules (a cancel from
within the consumer's own delivery is `success`, from anywhere else `cancelled`) are in the
[WebClient guide §4.2](https://github.com/Inqudium/legatium/blob/main/legatium-webclient-logging/docs/GUIDE.md#42-cancellation-and-the-missing-status).

## 3. What legatium logs

The blocking twin logs **`adapter_outcome=cancelled` at WARN, without a cause** when the call threw
and:

1. the thread was interrupted at the moment the exception reached the interceptor, or
2. the exception's cause chain contains an `InterruptedException` or a `ClosedByInterruptException`,
   or the JDK's `SocketException("Closed by interrupt")` or Apache's
   `RequestFailedException("Request aborted")`: the fallback for a flag something already consumed.

The cancellation wins over a timeout the transport reported in the same moment. A call that got its
answer despite the interrupt is classified by its status: the platform-thread rows above log
`success` (or `rejected` / `failure` by status) because the answer arrived before anybody stopped
waiting for it. The WARN breadcrumb on
`eu.inqudium.legatium.restclient.logging.ClientRequestLoggingInterceptor` still names the exception the
engine threw, which is where the differences of §2.2 remain visible.

## 4. Consequences for operating it

- **A `TimeLimiter` on platform threads does not free the worker with `HttpURLConnection` or
  Apache.** The caller is released when the budget expires, but the worker stays blocked in the socket
  read until the answer or the engine's **read timeout**. Under load those threads pile up. Configure
  a read timeout at most as long as the budget, or run the calls on virtual threads, or use an engine
  that waits on a future (JDK `HttpClient`, Jetty, Reactor Netty).
- **The `cancelled` count depends on the engine.** The same TimeLimiter setup produces `cancelled`
  lines with the JDK client and `success` lines (late ones, with a long `adapter_duration_ms`) with
  `HttpURLConnection` on platform threads. Compare `events{outcome="cancelled"}` across services only
  when they use the same engine and thread model.
- **The peer never learns.** An interrupt ends the wait on our side. The peer keeps processing the
  request, and its work and side effects happen anyway. A cancelled non-idempotent call may have been
  executed.
- **Catch `CancellationException` too** when an Apache client runs on virtual threads: otherwise the
  abandoned call escapes the application's `ResourceAccessException` handling.
- **Leave the interrupt flag alone in your own interceptors.** A host interceptor that catches the
  `InterruptedException` without restoring the flag pushes legatium onto the cause-chain fallback; one
  that also swallows the exception makes the call look like whatever it returns instead.

## 5. Recommendation by cancellation behaviour

Ranked by how well each engine honours an abandoned call, and by nothing else. Tested with Spring
Boot 4.1.1 (Spring Framework 7.0.9) on JDK 26.0.1:

| Rank | Engine (Spring request factory) | Tested version | Platform thread | Virtual thread | Thrown to the caller | Why |
|---|---|---|---|---|---|---|
| 1 | JDK `HttpClient` (`JdkClientHttpRequestFactory`) | JDK 26.0.1 | `cancelled` at once | `cancelled` at once | `ResourceAccessException` | honours the interrupt on both thread kinds, a clean exception, the flag restored, no extra dependency |
| 2 | Jetty (`JettyClientHttpRequestFactory`) | jetty-client 12.1.12 | `cancelled` at once | `cancelled` at once | `ResourceAccessException` | the same behaviour as rank 1, at the cost of the Jetty dependency |
| 3 | Reactor Netty (`ReactorClientHttpRequestFactory`) | reactor-netty-http 1.3.7 | `cancelled` at once | `cancelled` at once | `ResourceAccessException` | cancels cleanly, but brings Netty and its event loop for a single `block()` |
| 4 | `HttpURLConnection` (`SimpleClientHttpRequestFactory`) | JDK 26.0.1 | ignored: waits for the answer, logs `success` | `cancelled` at once | nothing on a platform thread; `ResourceAccessException` on a virtual one | usable only on virtual threads or with a short read timeout |
| 5 | Apache HttpClient 4 (own adapter, §2.3) | httpclient 4.5.14 | ignored: waits for the answer, logs `success` | `cancelled` after three internal retries | `ResourceAccessException` | behaves like rank 4 with a clean exception, but needs an adapter of its own and is end-of-life; the wait for a pooled connection cancels cleanly on both thread kinds |
| 6 | Apache HttpClient 5 (`HttpComponentsClientHttpRequestFactory`) | httpclient5 5.6.4 | ignored: waits for the answer, logs `success` | `cancelled` after an internal retry | a raw `CancellationException` on a virtual thread, not wrapped | does not free a platform thread, and its exception bypasses the usual error handling; only the wait for a pooled connection cancels cleanly on both thread kinds (`ResourceAccessException`) |

The ranking covers cancellation only. Connection-pool control, HTTP/2 or proxy support can rank the
engines differently, and Apache often comes out on top there. A team that keeps Apache sets the read
timeout no longer than the `TimeLimiter` budget and catches `CancellationException` (§4). On virtual
threads, ranks 1 to 5 are equivalent. No engine stops the peer's work: for a non-idempotent call the
choice of engine changes nothing about whether it was executed.

The versions are those the table was last verified against. A dependency upgrade that changes an
engine's behaviour fails the suites - the classification and the exceptions of §2.2 are both pinned
(§7) - and this table is then updated together with the suite's expectation. Apache HttpClient 4's
row is the exception: an observation of 2026-10-21 that nothing re-checks (§2.3).

## 6. Which engine you get by default

The ranking of §5 matters only if you know which engine a client actually runs on. Spring picks it
from the classpath, and the detection order is close to the reverse of the ranking. Checked against
the sources of Spring Boot 4.1.1 (`spring-boot-http-client`, `spring-boot-restclient`) and Spring
Framework 7.0.9.

### 6.1 The detection order

`ClientHttpRequestFactoryBuilder.detect()` takes the first engine whose class is on the classpath:

| Order | Engine | Detected by | Cancellation rank (§5) |
|---|---|---|---|
| 1 | Apache HttpClient 5 | `org.apache.hc.client5.http.impl.classic.HttpClients` | 6 |
| 2 | Jetty | `org.eclipse.jetty.client.HttpClient` | 2 |
| 3 | Reactor Netty | `reactor.netty.http.client.HttpClient` | 3 |
| 4 | JDK `HttpClient` | `java.net.http.HttpClient`, so practically always | 1 |
| 5 | `HttpURLConnection` | the fallback | 4 |

Apache HttpClient 4 (rank 5) is not detected: Spring has no factory for it, so it runs only where a
host sets its own adapter with `requestFactory(...)`.

**An Apache jar on the classpath wins**, even when it arrived only transitively through some other
library. Without a decision of its own, an application then runs on the engine that handles
cancellation worst. Check with `mvn dependency:tree -Dincludes=org.apache.httpcomponents.client5`
whether httpclient5 is on your runtime classpath.

### 6.2 Choosing the engine explicitly

In a servlet or non-web application, one property sets the engine for Boot's `RestClient.Builder`
and `RestTemplateBuilder`:

```yaml
spring:
  http:
    clients:
      imperative:
        factory: jdk   # http-components | jetty | reactor | jdk | simple
```

The Boot 3 name `spring.http.client.factory` has been deprecated since 4.0.0, with this property as
its replacement. Use the new name.

### 6.3 Where the property does not apply

| Setup | What decides the engine |
|---|---|
| Boot's `RestClient.Builder` in a servlet or non-web application | `spring.http.clients.imperative.factory`, otherwise the detection of §6.1 (`ImperativeHttpClientAutoConfiguration`) |
| Boot's `RestClient.Builder` in a **reactive (WebFlux) application** | the **detection of §6.1 only**: `ImperativeHttpClientAutoConfiguration` is switched off there (`NotReactiveWebApplicationCondition`), so the property is ignored and `RestClientAutoConfiguration` calls `detect()` itself. Provide your own `ClientHttpRequestFactoryBuilder` bean, or set `requestFactory(...)` on the builder |
| Boot's `RestTemplateBuilder` | the property in a servlet or non-web application; `RestTemplateAutoConfiguration` is not active in a reactive one. `detectRequestFactory(false)` keeps `RestTemplate`'s own default |
| `RestClient.builder()` without Boot | Spring Framework's own detection in `DefaultRestClientBuilder`, in the same order as §6.1 |
| `new RestTemplate()` without Boot | always `SimpleClientHttpRequestFactory` (`HttpAccessor`), i.e. `HttpURLConnection` |
| `requestFactory(...)` set on any builder | exactly that factory |

A client the host builds with an explicit `requestFactory(...)` is independent of all of this, and so
is every engine suite of this repository: each sets its factory explicitly.

## 7. How this is pinned

| Behaviour | Engines | Test |
|---|---|---|
| virtual thread, interrupted mid-call | all five: JDK `HttpClient`, Jetty, Reactor Netty, `HttpURLConnection`, Apache 5, each expecting `cancelled` | `RequestFactoryContract`: `should log a caller interrupted on a virtual thread as WARN cancelled …` |
| platform thread, interrupted mid-call | all five: JDK `HttpClient`, Jetty and Reactor Netty expecting `cancelled`; `HttpURLConnection` and Apache 5 expecting `success` (`interruptibleOnPlatformThreads = false`) | `RequestFactoryContract`: `should log a caller interrupted on a platform thread the way this request factory honours it` |
| `Future.cancel(true)` on a virtual worker | all five, each expecting `cancelled` | `RequestFactoryContract`: `should log a worker cancelled through its future as WARN cancelled …` |
| interrupted pool lease | Apache 5 | `HttpComponentsRequestFactoryIntegrationTest`: `should log a caller interrupted while waiting for a pooled connection as WARN cancelled` |
| pool lease timeout (the control: a timeout, not a cancellation) | Apache 5 | `HttpComponentsRequestFactoryIntegrationTest`: `should log this request factory's pool lease timeout as WARN timeout` |
| Apache 4's timeout classes as `timeout` | Apache 4 (by class name, no engine run) | `TimeoutsTest`: `should recognise Apache HttpClient 4's connect and pool lease timeouts by their class name` |
| the exceptions of §2.2 - at the interceptor and at the caller - on a virtual thread, a platform thread and through `Future.cancel(true)` | all five, each against its suite's `abandonedCallExceptions` (none at all on a platform thread for `HttpURLConnection` and Apache 5) | `RequestFactoryContract`: `should throw what the cancellation page documents when …` (three scenarios) |
| the exceptions of §2.2 for the interrupted pool lease | Apache 5 | `HttpComponentsRequestFactoryIntegrationTest`: `should throw what the cancellation page documents when a caller is interrupted waiting for a pooled connection` |
| classification order, flag never cleared, close on another thread | none: engine-independent, driven by mock executions and hand-built exceptions | `ClientRequestLoggingInterceptorCancellationTest`, `CancellationsTest` |

`RequestFactoryContract` runs once per engine, through its subclasses `JdkClientRequestFactoryIntegrationTest`,
`JettyRequestFactoryIntegrationTest`, `ReactorNettyRequestFactoryIntegrationTest`,
`SimpleRequestFactoryIntegrationTest` (`HttpURLConnection`) and `HttpComponentsRequestFactoryIntegrationTest`.
Apache HttpClient 4's rows in §2 and §5 are the observation of 2026-10-21 (§2.3) and are not re-run.

The exception types in §2.2 are not part of legatium's contract - the line legatium writes does not
depend on them - but they are what an operator catches, so they are pinned in scenarios of their own:
the exact type at the interceptor (with the message prefix and the direct cause where §2.2 names
them) and the exact type thrown to the caller. A new engine version that throws something else fails
those scenarios, and this page is updated with the expectation. The retry counts of the Apache
engines are not pinned.
