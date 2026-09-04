# Module legatium-restclient-logging

Auto-configured `RestClient`/`RestTemplate` interceptor for Spring Boot
applications that logs one structured `adapter_*` line per outbound HTTP
exchange and carries the exchange identity in the MDC while the wire call
runs. The field-and-configuration-identical WebClient twin is
`legatium-webclient-logging`; the long-form guide lives in
[docs/GUIDE.md](https://github.com/Inqudium/legatium/blob/main/legatium-restclient-logging/docs/GUIDE.md).
