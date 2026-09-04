# ADR-0007: The operator-facing vocabulary is `adapter`, the counterpart of `endpoint`

- **Status:** accepted
- **Date:** 2026-09-04
- **Context:** Everything an operator sees carried the prefix `client`: the
  log fields (`client_outcome`, `client_url_host`, ...), the MDC keys
  (`client_request_id`, `client_route`, `client_method`), the meters
  (`client.logging.*`, `client.request.body.size`, ...), the logger name
  `http-client-exchange` and the property namespace `client-logging.*`.
  That followed Spring's own pair (`http.server.requests` /
  `http.client.requests`), but it collides with ECS, where `client.*` names
  the REMOTE party of an inbound connection. In an index that holds
  limesium's `endpoint_*` lines and legatium's lines side by side, "client"
  points a reader the wrong way for a moment: there it is the caller, here
  it is us. Candidates for a replacement were weighed on the axis they pair
  on with limesium's `endpoint`: `upstream` is established in proxies
  (nginx, Envoy) but hop-relative - on an inbound sidecar the "upstream" is
  the local application, and some APM service maps use the word for the
  callers - and limesium is not "downstream"; `dependency` pairs well with
  App Insights' requests/dependencies but is too loaded with Maven meaning
  in a Java project; `outbound`/`egress` presuppose an `inbound`/`ingress`
  twin that does not exist.

## Decision

**The prefix is `adapter`, everywhere an operator reads it; code names stay.**

1. `endpoint` and `adapter` name the two places in an application where
   the outside world is attached: the endpoint is where a foreign party
   calls us (limesium), the adapter is where we call a foreign party and
   adapt it to the application (legatium). Both are places, not roles or
   directions, so the pair holds on one axis. Within the Inqudium
   vocabulary "adapter" is the driven side by definition; that a controller
   is also an adapter in hexagonal purism does not matter once the pair is
   fixed here.
2. The whole operator surface moves in one step, so nobody maps three
   vocabularies onto each other: log fields `adapter_*`, MDC keys
   `adapter_request_id` / `adapter_method` / `adapter_route`, meters
   `adapter.logging.*` / `adapter.request.body.size` /
   `adapter.response.body.size` / `adapter.response.body.read`, logger
   `adapter-http-exchange`, property namespace `adapter-logging.*`, the
   Elasticsearch component template and the reference configuration.
3. Code names are unchanged: packages, `ClientLogField`,
   `ClientLoggingProperties`, `ClientRequestLoggingInterceptor`,
   `ClientRequestLoggingFilter`, module and Maven names. They describe what
   the classes technically are - Spring HTTP-client instrumentation - and
   are read by developers, not on log lines.

## Consequences

- One vocabulary on the line: an inbound request's own `endpoint_*` keys
  and the `adapter_*` keys of the calls it makes sit next to each other in
  the MDC and read as the pair they are.
- No ECS collision: `adapter.*` is not an ECS field set, `client.*` is.
- No migration: the rename lands before the first release. The ELK README
  states the ECS distinction once, for readers who arrive with the ECS
  meaning of "client" in mind.

## Amendment (2026-09-05): the logger name leads with the vocabulary word

The default logger name was `http-adapter-exchange`; it is now
`adapter-http-exchange`, so that the logger, like every field, MDC key,
meter and property of this family, starts with `adapter`. An operator
filtering a log index by prefix sees the adapter family as one block, and
limesium's `endpoint-http-exchange` is the inbound block beside it. Still before the
first release, so no migration; the reference configuration, the guides and
the twin message tests carry the new default.
