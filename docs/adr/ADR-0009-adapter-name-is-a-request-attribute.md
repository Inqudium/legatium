# ADR-0009: A client's name is a request attribute, logged as `adapter_name`

**Status:** Accepted  
**Date:** 2026-09-16  
**Deciders:** Dirk Haase (maintainer)  
**Related:** ADR-0003 (`AdapterName` and the `NAME` field live in
`legatium-common`, one implementation for both twins), ADR-0007
(`adapter_name` follows the family's prefix), ADR-0008 (the `name`
tag joins `uri` and `host` on the body meters, no new meter)

## Context

Every line the modules write locates the peer by `adapter_url_host`,
taken from the host of the request URI. That is the right coordinate
as long as the URI names the dependency: "which dependency is slow"
is a question about that field, and the body meters carry it as
their `host` tag.

It stops being one the moment the application reaches its
dependencies through an **egress sidecar** or a forward proxy. The
URI then names the sidecar (`http://localhost:15001/billing/...`,
`http://egress:8080/geo/...`) for every call, and every dependency
collapses into the same `adapter_url_host` bucket - on the log line
and on the meters alike. The path is no substitute: it is
high-cardinality by design (`doc_values: false`) and its prefix
convention is the deployment's, not the module's. A transparent mesh
(an iptables redirect into a sidecar) does not have the problem, the
URI still names the service there; the explicit-proxy topology does,
and it is common enough to need an answer.

The application knows which client made the call. It builds one
`RestClient` or `WebClient` per dependency, and it has a name for
each of them - `billing`, `geo-lookup` - that is more stable than any
host name anyway. The module only lacks a way to be told.

Four ways to tell it were weighed:

1. **A request attribute** the host sets once per client builder
   (`defaultRequest { it.attribute(NAME, "billing") }`), which every
   request of that client then carries. Both twins already read a
   request attribute at wiring time - the URI template - so the
   mechanism exists on both stacks.
2. **A configurable peer header**, e.g. `adapter-logging.peer-host-header:
   Host`: when the request carries that header, its value replaces the
   URI host in `adapter_url_host`. Fits a host that already routes
   through the sidecar by `Host` header.
3. **A path-prefix rule** in the configuration, mapping
   `/billing/**` to `billing`.
4. **An application-wide property** naming the application's one
   client - which does not distinguish anything, and is dismissed
   immediately.

## Decision

We adopt option 1: **the name of a client is a request attribute, set
by the host, and logged as its own field `adapter_name`.**

### The attribute

`AdapterName.ATTRIBUTE` is the string
`eu.inqudium.legatium.adapterName` - deliberately ONE constant for both
twins, so a host carrying both jars names its clients with one
literal; each twin re-exports it as `ADAPTER_NAME_ATTRIBUTE` on its
public entry class (`ClientRequestLoggingInterceptor`,
`ClientRequestLoggingFilter`). The host sets it once per client:

```kotlin
RestClient.builder()
    .baseUrl("http://localhost:15001/billing")
    .defaultRequest { it.attribute(ClientRequestLoggingInterceptor.ADAPTER_NAME_ATTRIBUTE, "billing") }
    .build()

WebClient.builder()
    .baseUrl("http://localhost:15001/geo")
    .defaultRequest { it.attribute(ClientRequestLoggingFilter.ADAPTER_NAME_ATTRIBUTE, "geo-lookup") }
    .build()
```

A `RestTemplate` has no `defaultRequest`; an interceptor of the host's
own, registered before the logging interceptor, sets
`request.attributes[ADAPTER_NAME_ATTRIBUTE]` instead. A per-call
`attribute(...)` on a request spec overrides the builder default, which
is the escape hatch for a client that serves two dependencies.

The value is read at wiring time (`AdapterName.of`): the string
itself, or **no name** when the attribute is absent, not a string, or
blank - a blank name would be an empty bucket that only looks like a
client. Nothing is validated beyond that and nothing is folded: the
name is the host's vocabulary, and a host that puts a per-call value
there gets the cardinality it asked for.

### The field and the tag

`adapter_name` joins the `ClientLogField` family as a plain `keyword`
with doc values - it exists to be grouped by - present on the
completion event and the arrival line only when the host named the
client. `adapter_url_host` is **not** replaced and keeps its meaning:
the host on the wire, sidecar or not. The two answer different
questions, and behind a sidecar the operator now has both.

The three body meters (`adapter.request.body.size`,
`adapter.response.body.size`, `adapter.response.body.read`) gain a
`name` tag beside `uri` and `host`, so the "unread share per call site"
question stays answerable behind a sidecar too. A client without a name
is tagged `UNNAMED` - not `UNKNOWN`, because the name is not unknown, it
was never given - and the tag is always present, so every meter of a
name carries the same tag keys (a registry with a Prometheus backend
rejects the alternative). No meter is added (ADR-0008).

### The message stays as it is

The message text (`Adapter http exchange POST http://... -> 200 [...]`)
does not carry the name. The message is the plain-text fallback for
an appender without key-values; a reader with only that still has the
URL, and the pinned text (`TwinContractTest`) does not move for an
optional field.

## Consequences

**Positive:**

- Several clients behind one sidecar are distinguishable on the log
  line and on the body meters, by the name the application already
  has for them - and the same field distinguishes several clients of
  one application in front of *different* hosts, which the URL host
  did as a side effect only.
- Nothing goes on the wire: the module keeps observing the request
  instead of shaping it, and the peer sees no new header.
- No configuration: the name lives where the client is built, next to
  its base URL and its authentication, and travels with the builder
  wherever it is injected.
- The mechanism is the one the URI template already uses; there is no
  second way for the host to talk to the module.

**Negative:**

- The host has to opt in per client. A client nobody named logs no
  `adapter_name` and meters under `name=UNNAMED`; the sidecar problem
  is solved only where the host does its part.
- One more field in the family and one more tag key on three meters -
  both are external contracts (the component template, dashboards
  keyed on the tag set). The template ships the mapping, the lockstep
  test pins it; a dashboard that selects meters by their full tag set
  needs the new key.
- Two twins, two public constants for one string; the shared literal
  is pinned by `SharedContractTest`, the re-exports are compile-time
  constants of it.

**Neutral:**

- Option 2 (a peer header) is not taken but not excluded: it would
  serve a host that cannot touch its builders. It would make
  `adapter_url_host` mean two things depending on configuration,
  which is why the name got its own field first; a later ADR may add
  the header as a *source* of `adapter_name` rather than as a
  replacement of the host.
- Spring Boot's HTTP service groups (`@ImportHttpServices(group = …)`)
  carry a name per group that could feed the attribute automatically
  through a group configurer. That is a convenience on top of this
  decision, not part of it.
