## Context

JBH-55 section 3 (JBH-59). The gateway is Spring Boot 3.5.16, Spring Cloud Gateway 4.3.0 (WebFlux), Kotlin. Today: actuator `health,info` only, no tracing, no metrics, no `logback-spring.xml`.

Facts checked in the jars and metadata (Spring Boot 3.5.16, Gateway 4.3.0, Micrometer 1.15.12, Micrometer Tracing 1.5.12, OpenTelemetry SDK 1.49.0):

- Boot properties exist: `management.otlp.tracing.{endpoint,transport,export.enabled}`, `management.otlp.logging.{endpoint,transport,export.enabled}`, `management.tracing.{enabled,sampling.probability,baggage.enabled}`, `management.opentelemetry.resource-attributes`, `management.metrics.distribution.slo`, `spring.reactor.context-propagation`, `logging.structured.ecs.service.*`.
- Defaults to fix: `management.otlp.*.transport` is `http` (we need `grpc`); `management.tracing.sampling.probability` is `0.1` (we need `1.0`); export is on when the `export.enabled` property is missing (so we must set it).
- Gateway 4.3 prefix is `spring.cloud.gateway.server.webflux` (`GatewayProperties.PREFIX`). Keys checked: `metrics.enabled`, `observability.enabled` (default true), `default-filters`.
- Gateway route metric: timer `spring.cloud.gateway.requests` (Prometheus: `spring_cloud_gateway_requests_seconds_*`) with tags `routeId`, `routeUri`, `httpMethod`, `httpStatusCode`, `status`, `outcome`. The field `metrics.enabled` defaults to `false` in the metadata, but the auto-configuration uses `matchIfMissing = true`. We set it to `true` explicitly, so the result does not depend on this conflict.
- Gateway client observation `http.client.requests` (tags `spring.cloud.gateway.route.id`, route uri, method, status; high-cardinality attribute `http.uri` = full request URI with query).
- In Spring Framework 6.2, `HttpWebHandlerAdapter` starts the `http.server.requests` observation when the response `Mono` is subscribed. The observation context is in the exchange attribute `ServerRequestObservationContext.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE`. A `WebFilter.filter()` call runs at assembly time, before the span exists.
- `ServiceLevelObjectiveBoundary` parses `ms` as whole numbers. `2.5s` is not valid, so we write `2500ms`.
- Existing routes: five, all with `Path=/jbh-api/...`. No `discovery.locator`. No other `RouteLocator` code in `src/main`.
- `RateLimitConfig` logs only an invalid CIDR. It never logs `X-Forwarded-For`. `AuthorizationGlobalFilter` logs method, path, remote address and (DEBUG) user id. It never logs the token, cookie or `Authorization` header.

Interview outcomes (binding): export gate only; SRP only (no new interfaces); baggage dropped by config.

## Decisions

- **D1: `OTEL_SDK_DISABLED` controls only the export.** `management.otlp.tracing.export.enabled` and `management.otlp.logging.export.enabled` are `true` only when `OTEL_SDK_DISABLED=false`. `management.tracing.enabled` is not set (default `true`). So with export off the gateway still makes trace ids, returns `X-Trace-Id`, puts `traceId`/`spanId` in logs and sends `traceparent` downstream.
  **Deviation from the plan** ("map it to `management.tracing.enabled`"). Reason: `management.tracing.enabled=false` removes the `Tracer`. Then there is no trace id, no header, no log correlation and no `traceparent` downstream. The user decided that these must work in prod before JBH-84, while nothing is sent to a collector.
- **D2: Invert the flag with a nested placeholder lookup in `application.yml`.** Spring placeholders cannot negate a value. We use:

  ```yaml
  jbh:
    otel:
      # Lookup table: value of OTEL_SDK_DISABLED -> is export on?
      export-enabled-when-sdk-disabled:
        "true": false
        "false": true
      export-enabled: ${jbh.otel.export-enabled-when-sdk-disabled.${OTEL_SDK_DISABLED:true}:false}
  management:
    otlp:
      tracing:
        export:
          enabled: ${jbh.otel.export-enabled}
      logging:
        export:
          enabled: ${jbh.otel.export-enabled}
  ```

  The keys `"true"` and `"false"` are quoted, so YAML reads them as strings and the flat names are `jbh.otel.export-enabled-when-sdk-disabled.true` and `...false`. Behavior: unset -> `true` -> export off. `false` -> export on. Any other value (`banana`, empty, `FALSE`) -> key not found -> fallback `false` -> export off. No value can make startup fail.
  Rejected: an `EnvironmentPostProcessor` (a new class, an extra `spring.factories` entry, native-image hints, for one boolean); Spring profiles (an env value cannot choose a profile without more config); SpEL (not available in property files). Only the exact lower-case `false` turns export on. This is the safe direction.
- **D3: gRPC transport, one endpoint.** `management.otlp.tracing.transport: grpc` and `management.otlp.logging.transport: grpc`. Both endpoints are `${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:14317}` (Docker sets `http://jbh-alloy:4317`). With gRPC there is no path suffix. Alloy listens on gRPC at both ports. The gRPC channel connects lazily, so a missing collector does not block startup.
- **D4: Sampling and propagation.** `management.tracing.sampling.probability: ${OTEL_TRACES_SAMPLER_ARG:1.0}` (parent-based in Boot: a sampled parent stays sampled). Propagation stays W3C (`traceparent`), the Boot default for produce. An incoming `traceparent` from the internet is accepted (W3C behavior). Reason to accept: the trace id is not a secret and the plan asks that a trace starts in the browser.
- **D5: `X-Trace-Id` is set by a `WebFilter`, read lazily at commit time.**
  - Type: `WebFilter` with order `Ordered.HIGHEST_PRECEDENCE`. A `GlobalFilter` runs only for routed requests, so it would miss a 404 (no route) and every actuator response. `AuthorizationGlobalFilter` (order -1) is a `GlobalFilter` and writes the 401 inside the gateway chain. A `WebFilter` wraps all of it.
  - Header write: in `filter()` the filter registers `exchange.response.beforeCommit { set X-Trace-Id }` and then calls the chain. `beforeCommit` runs for every response path (401 written by the global filter, 429 from the rate limiter, 404, 5xx written by the error handler, a normal routed response). `set` (not `add`) keeps exactly one value even when a downstream service sends its own `X-Trace-Id` (same value, sections 4-6).
  - Source of the id: the server observation context from the exchange attribute `ServerRequestObservationContext.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE` (use `ServerRequestObservationContext.findCurrent(exchange.attributes)`), then the `TracingObservationHandler.TracingContext` entry in that context, then `getSpan().context().traceId()`. At commit time the observation has started, so the id exists. The exact method names must be confirmed in the Micrometer Tracing 1.5.12 jar during implementation.
  - **Deviation from the plan** ("use the Micrometer `Tracer` bean"): `Tracer.currentSpan()` reads a thread-local. At `filter()` time no span exists yet (assembly time), and on the commit thread the scope is restored only when context propagation happens to cover it. The observation context is the same object on every thread and does not depend on that.
  - No context or no span -> no header, no error (the filter must never fail a request).
- **D6: Drop `baggage` by config.** `management.tracing.baggage.enabled: false` (Boot does not read or write baggage) plus the gateway default filter `RemoveRequestHeader=baggage` under `spring.cloud.gateway.server.webflux.default-filters` (the gateway would otherwise forward the raw header to the services). Both keys exist in the current versions. A YAML comment gives the reason: an internet client must not be able to push baggage entries into our services. `traceparent` is still accepted (D4).
- **D7: Downstream `traceparent`.** The gateway's `ObservedRequestHttpHeadersFilter` and `GatewayPropagatingSenderTracingObservationHandler` are on by default (`observability.enabled`, `matchIfMissing`). They need an `ObservationRegistry`, a `Tracer`, a `Propagator` and `TracingProperties`. All exist once the tracing bridge is on the classpath. We set `spring.cloud.gateway.server.webflux.observability.enabled: true` explicitly so the intent is visible. Result: a routed call gets a client span (child of the server span) and the downstream request carries `traceparent`. Plan difference: none.
- **D8: Metrics.**
  - Dependency `micrometer-registry-prometheus`; `management.endpoints.web.exposure.include: health,info,prometheus`.
  - `management.metrics.tags.service: jbh-gateway` and `management.metrics.tags.env: ${JBH_ENV:local}`. Alloy uses `honor_labels = true` and also adds `service=jbh-gateway`. Both values are equal.
  - `spring.cloud.gateway.server.webflux.metrics.enabled: true` (explicit, see Context).
  - Histogram with the shared bucket list for the two meters named in the plan. Written as whole `ms` values (see Context):

    ```yaml
    management:
      metrics:
        distribution:
          slo:
            "[http.server.requests]": 5ms,10ms,25ms,50ms,100ms,250ms,500ms,1000ms,2500ms,5000ms,10000ms
            "[spring.cloud.gateway.requests]": 5ms,10ms,25ms,50ms,100ms,250ms,500ms,1000ms,2500ms,5000ms,10000ms
    ```

    With `slo` the registry publishes exactly these 11 buckets plus `+Inf`. We do not set `percentiles-histogram` (it would add about 70 buckets). `http.client.requests` gets no buckets (count, sum, max only) to save series.
  - Labels hold no user id, email, IP or raw path. `http.server.requests` tag `uri` is a pattern or `UNKNOWN`/`NOT_FOUND`/`REDIRECTION`/`root`; for routed gateway requests it is `UNKNOWN`, so per-endpoint latency at the gateway comes from `routeId`. The gateway tag `GatewayPathTagsProvider` (raw path) stays off: `metrics.tags.path.enabled` is not set. `routeUri` is the `lb://service` value (5 values).
- **D9: Remove the query string from the gateway client span attribute `http.uri`.** `DefaultGatewayObservationConvention` puts `request.getURI().toString()` into the high-cardinality key `http.uri`. That string has the query. The plan's own check is `.../checkEmailAvailability?email=x@y.z`: the email would land in Tempo. The plan rule says no personal data in span attributes. Fix: a Micrometer `ObservationFilter` bean (a Micrometer interface; no new interface of ours) that, for a `GatewayContext`, replaces `http.uri` with the URI without query. Boot adds every `ObservationFilter` bean to the registry. The server span attribute `http.url` is the path only (checked in `DefaultServerRequestObservationConvention`), so it needs no fix. Rejected: turning `observability.enabled` off (loses D7); a custom `GatewayObservationConvention` (a bigger class that copies the default one).
  **New file not in the interview list** (security rule forces it). The leader should confirm it. If the user rejects it, the fallback is to accept the risk and say so in the docs.
- **D10: Resource attributes** under `management.opentelemetry.resource-attributes` (bracket keys, because the names hold dots):

  ```yaml
  management:
    opentelemetry:
      resource-attributes:
        "[service.namespace]": jbh
        "[deployment.environment.name]": ${JBH_ENV:local}
        "[service.version]": ${JBH_SERVICE_VERSION:unknown}
  ```

  `service.name` comes from `spring.application.name` (`jbh-gateway`) by Boot default. `service.version`: the image does not carry the git SHA today, so the env var `JBH_SERVICE_VERSION` (default `unknown`) is the contract; JBH-84 can set it. `logging.structured.ecs.service.environment: ${JBH_ENV:local}` and `...ecs.service.version: ${JBH_SERVICE_VERSION:unknown}` give the JSON logs the same values.
- **D11: `spring.reactor.context-propagation: auto`.** Without it, logs written in reactive code miss `traceId`/`spanId` (MDC is thread-local). Add `io.micrometer:context-propagation` (Boot-managed version) if the dependency report shows it is not already on the classpath.
- **D12: `logback-spring.xml` (one job: log format per profile).**
  - Console text for every profile except `prod`: pattern holds `%X{traceId:-}` and `%X{spanId:-}` (explicit in the XML, so it does not depend on a Boot default property).
  - Profile `prod`: console appender with `org.springframework.boot.logging.logback.StructuredLogEncoder`, `<format>ecs</format>`. The JSON line must carry the trace id and span id (the implementer checks the field names in the Boot 3.5 ECS writer; either `traceId` from MDC or ECS `trace.id` is accepted).
  - `OpenTelemetryAppender` (`io.opentelemetry.instrumentation.logback.appender.v1_0`) attached to `root` in every profile. Captured attributes: only the defaults (no MDC, no key-value pairs, no code attributes, no arguments). The appender buffers early log events (default 1000) until the install in D13 and then replays them.
  - **No feedback loop.** OTLP exporter errors are logged by `io.opentelemetry.*`, `io.grpc.*`, `okhttp3.*` and `okio.*` (Java util logging, which Boot bridges to Logback). Those loggers are declared with `additivity="false"` and only the console appender. Their events never reach `root`, so never reach the OTel appender.
  - **Missing collector.** Startup: the gRPC channel is lazy, so nothing blocks. At run time: export is asynchronous in a batch processor with a bounded queue (default 2048 spans / logs). When the queue is full it drops records. The exporter's own warnings are throttled by the SDK. Together with the exclusion above, the log shows a few lines per minute at most, not a flood, and no request waits.
  - With export off, no exporter exists. The appender converts log events and hands them to a logger provider without a processor. Cost is small and nothing leaves the process.
- **D13: `OpenTelemetryAppenderConfig` (one job: install the appender).** A Spring component that implements `InitializingBean` and calls `OpenTelemetryAppender.install(openTelemetry)` with the Spring `OpenTelemetry` bean (as the Boot docs show). Calling `install` again (several test contexts in one JVM) is safe.
- **D14: Dependencies** in `build.gradle.kts`: `io.micrometer:micrometer-tracing-bridge-otel`, `io.opentelemetry:opentelemetry-exporter-otlp`, `io.micrometer:micrometer-registry-prometheus` (versions from the Spring Boot BOM) and `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.15.0-alpha` (pinned; the Boot BOM does not manage it). `2.15.0-alpha` is the instrumentation line built for OpenTelemetry SDK 1.49.0, the version the Boot 3.5.16 BOM manages; the implementer confirms it in the resolved POM and does not choose a line that needs a newer SDK.
- **D15: Security rules (state and check).**
  - **No route matches `/actuator/**`.** Checked: all five routes use `Path=/jbh-api/...`, `spring.cloud.gateway.server.webflux.discovery.locator` is not set (default off), and `src/main` has no `RouteLocator` or `RouteDefinitionLocator` code (CodeGraph search and file list). The implementer repeats this check after the YAML edit.
  - `/actuator/prometheus` is not behind the JWT filter: `AuthorizationGlobalFilter` is a `GlobalFilter`, and global filters run only for routed requests. The endpoint is a WebFlux actuator handler, not a route.
  - Reach: locally, host port 8080 is published by compose (local only). In prod, Caddy forwards only `/jbh-api/*`, so `/actuator/prometheus` is reachable only from Docker networks.
  - No JWT, cookie or `Authorization` in logs or span attributes: no new log statement logs them. The server span attribute `http.url` has the path only. D9 removes the query from the client span. Do not enable `spring.codec.log-request-details` or TRACE logging for `org.springframework.web` (header values would appear).
  - The rate limiter does not log `X-Forwarded-For` (checked in `RateLimitConfig.kt`). No change there.
  - Existing DEBUG line in `AuthorizationGlobalFilter` logs the user id. DEBUG is off by default. Not changed here.
- **D16: Series estimate for `/actuator/prometheus`** (for the plan's budget table). One timer with SLO buckets = 11 buckets + `+Inf` + count + sum + max = 15 series per tag combination.

  | Group | Active combinations | Series |
  |---|---|---|
  | JVM, process, system, logback, executor | - | ~150 |
  | `http_server_requests` (method x status x outcome, `uri=UNKNOWN`) + actuator | ~30 + ~3 | ~500 |
  | `spring_cloud_gateway_requests` (5 routes x method x status) | ~40 | ~600 |
  | `http_client_requests` (no buckets: count, sum, max) | ~40 | ~120 |
  | `spring_cloud_gateway_routes_count`, misc | - | ~5 |
  | Total | | ~1,400 worst case, ~700 typical |

  Target for the gateway: at most 1,500 (a quarter of the 6,000 budget). The user measures: `curl -s localhost:8080/actuator/prometheus | grep -vc '^#'`. If over budget, the first cut is the histogram on `spring.cloud.gateway.requests`.
- **D17: Accepted noise.** Alloy scrapes every 30 s and Consul checks `/actuator/health` every 60 s. Each makes a server span, about 3 per minute. Excluding them needs an `ObservationPredicate` class. Not done in this change.

## Responsibilities

- `filter/TraceIdResponseFilter.kt` (new): adds `X-Trace-Id` to every response. Changes when the header contract changes.
- `config/OpenTelemetryAppenderConfig.kt` (new): installs the OpenTelemetry Logback appender with the Spring `OpenTelemetry` bean at start. Changes when log export wiring changes.
- `observation/` filter class (new, one file, name chosen by the implementer, for example `GatewayClientUriObservationFilter.kt`): removes the query string from the client span attribute `http.uri`. Changes when the privacy rule for span attributes changes. D9.
- `src/main/resources/logback-spring.xml` (new): log format per profile and the appender wiring. Changes when the log format changes.
- `application.yml` (existing, new job: telemetry config) and `build.gradle.kts` (existing, new libraries): hold config and libraries only. No code.
- No new interface and no abstraction (SRP only, user decision).

## Database

No database change.

## Concurrency

none. Reasons:

- `TraceIdResponseFilter`: no shared state. The `beforeCommit` callback runs once per exchange and reads that exchange's own attributes.
- `OpenTelemetryAppenderConfig`: `install` runs once at bean start. The library buffers and replays early events and is thread-safe. A second `install` (several contexts in one test JVM) is harmless.
- The observation filter changes only the context it receives.
- Export is asynchronous inside the OpenTelemetry SDK (library code, bounded queue). We add no thread, no shared cache, no check-then-act.

## Test contract

- **Test blueprint: does not apply.** The JBH-55 plan excludes unit tests, and this change has no unit-testable logic of its own (config, one small filter that needs the Spring observation context, one XML file). No unit tests, so tasks.md has no test blueprint group.
- **Automated checks:** the build (`./gradlew build`), the existing suite (`./gradlew test`, must stay green; `JbhGatewayApplicationTests` loads the whole context with export off and no collector, so the new beans and the YAML placeholders must wire), and the complexity check from `CLAUDE.md`.
- **Manual checks (user), with expected values.** Start the gateway with `./gradlew bootRun` and `OTEL_SDK_DISABLED=false` unless stated.

  | # | Input | Expected result |
  |---|---|---|
  | M1 | `curl -i 'http://localhost:8080/jbh-api/auth/checkEmailAvailability?email=x@y.z'` | Response has `X-Trace-Id` with 32 lowercase hex characters (once). |
  | M2 | `curl -i http://localhost:8080/jbh-api/users/me` (no token) | `401` and `X-Trace-Id`. |
  | M3 | `curl -i http://localhost:8080/jbh-api/nope` and `curl -i http://localhost:8080/other` (no route matches, so no global filter runs) | `404` and `X-Trace-Id` for both. |
  | M4 | 11 requests in one second to `/jbh-api/auth/checkEmailAvailability` | The 11th is `429` and has `X-Trace-Id`. |
  | M5 | `curl -i -H 'traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01' http://localhost:8080/jbh-api/auth/checkEmailAvailability?email=x@y.z` | `X-Trace-Id: 0af7651916cd43dd8448eb211c80319c`. |
  | M6 | `curl -s http://localhost:8080/actuator/prometheus` | Has `http_server_requests_seconds_bucket` with exactly the `le` values `0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, +Inf`; same for `spring_cloud_gateway_requests_seconds_bucket` with `routeId="auth_api_route"`; labels `service="jbh-gateway"` and `env="local"`; no label value holds a raw path, query, IP or `x@y.z` (`grep -c 'x@y.z'` prints `0`). |
  | M7 | `curl -i http://localhost:8080/actuator/prometheus` | `200`, no token needed. |
  | M8 | Start with `OTEL_SDK_DISABLED` unset, then M1 and M2 | Gateway starts. `X-Trace-Id` is present. Log lines show a non-empty `traceId`. No connection to any collector is tried. |
  | M9 | Start with `OTEL_SDK_DISABLED=banana` | Gateway starts, export is off (same as M8). |
  | M10 | Start with `OTEL_SDK_DISABLED=false` and no collector running | Gateway starts and `/actuator/health` is `UP`. The log has a few exporter warnings per minute at most, and none of them is repeated as an OTLP log. Requests are not slowed. |
  | M11 | Grafana Explore, Tempo, search the trace id from M1 | One trace. Gateway server span with resource `service.name=jbh-gateway`, `service.namespace=jbh`, `deployment.environment.name=local`. A client span child. The client span `http.uri` has no `?email=...`. |
  | M12 | Grafana Explore, Loki `{service_name="jbh-gateway"} \|= "<trace id from M1>"` | At least one line, with `traceId` and `spanId`. |
  | M13 | Run with `SPRING_PROFILES_ACTIVE=prod` | stdout lines are JSON (ECS) and contain the trace id; the same lines reach Loki. |
  | M14 | Series count: `curl -s localhost:8080/actuator/prometheus \| grep -vc '^#'` | At most about 1,500 (D16). Report the number to the leader for the plan's budget table. |

- **Baggage check (optional):** send `-H 'baggage: k=v'` through a route to a service that logs request headers. The service must not receive `baggage`.
- **Integration tests:** none added. The existing suite is the regression guard.

## Risks / Trade-offs

- `GatewayContext` and the `TracingContext` API names come from reading the jars and from memory of Micrometer. The implementer must confirm them (Context7 or the resolved jars) before writing code.
- The appender is an alpha artifact. Pinned, so a Boot upgrade does not move it silently.
- `GraalVM nativeCompile` with the new libraries is not checked here.
- If `management.tracing.sampling.probability` is lowered later, `X-Trace-Id` still shows the id of an unsampled trace. The id then has no trace in Tempo. Acceptable.
