## Why

Plan JBH-55 (section 3, sub-issue JBH-59) gives the JBH system one trace per request, metrics, and logs in Loki. The gateway is the first hop after the browser, so it starts the trace when the browser sends none. Today it has no tracing, no metrics, no structured logs, and no way to tell a client the trace id. Goals: G1, G2, G3, G4, G6, G9.

Section 1 (Alloy in `jbh-deploy`) is already merged. It scrapes `jbh-gateway` at `/actuator/prometheus` (port 8080) and listens for OTLP gRPC on `jbh-alloy:4317` (Docker) and `localhost:14317` (IDE).

## What Changes

- **Dependencies** (`build.gradle.kts`): Micrometer tracing bridge to OpenTelemetry, OTLP exporter, Prometheus registry, OpenTelemetry Logback appender (pinned).
- **Trace**: the gateway continues an incoming `traceparent`, or starts a new trace. Routed calls carry `traceparent` downstream. Incoming `baggage` is dropped.
- **Export gate**: `OTEL_SDK_DISABLED` (default `true`) turns only the sending of traces and logs on or off. Trace ids, `X-Trace-Id`, log correlation and downstream `traceparent` work in both states. This differs from the plan text (see design D1).
- **Header**: new `TraceIdResponseFilter` adds `X-Trace-Id` to every response, errors and 404 included.
- **Metrics**: `/actuator/prometheus` with tags `service` and `env`, and the shared latency bucket list for `http.server.requests` and `spring.cloud.gateway.requests` (tag `routeId`).
- **Logs**: new `logback-spring.xml` (text with `traceId`/`spanId` for non-prod, ECS JSON for `prod`, OpenTelemetry appender in every profile) and `OpenTelemetryAppenderConfig` (installs the appender).
- **Privacy**: new small filter that removes the query string from the gateway client span attribute `http.uri` (it can hold an email).
- **Resource attributes**: `service.name`, `service.namespace=jbh`, `deployment.environment.name`, `service.version`.
- **Docs (leader)**: "Observability" section in `CLAUDE.md` and `README.md`; one line in `../jbh-deploy/README.md`.

## Non-goals

- No unit tests (the plan excludes them). No test blueprint group.
- No change to routes, `publicPaths`, JWT checks, or rate-limit beans.
- No prod export (ticket JBH-84). Prod default stays export off.
- No change in `jbh-deploy` compose or Alloy files (section 1 did it).
- No Dockerfile or CI change to bake the git SHA into the image. `service.version` reads env `JBH_SERVICE_VERSION` (default `unknown`); JBH-84 can set it.
- No `Access-Control-Expose-Headers` for `X-Trace-Id`. Same-origin dev proxy and Caddy do not need it.
- No `GraalVM nativeCompile` check for the new libraries.

## Capabilities

### New Capabilities
- `gateway-observability`: trace id and `X-Trace-Id` header, export gate, Prometheus metrics, log correlation and OTLP logs, and the privacy rules for telemetry.

### Modified Capabilities
None. `gateway-routing` is unchanged (no route is added or changed).

## Impact

- New files: `filter/TraceIdResponseFilter.kt`, `config/OpenTelemetryAppenderConfig.kt`, one observation filter class, `src/main/resources/logback-spring.xml`.
- Changed files: `application.yml`, `build.gradle.kts`.
- Larger jar (OpenTelemetry SDK, OTLP exporter, Prometheus client).
- A server span for each Alloy scrape (every 30 s) and each Consul health check (every 60 s). Accepted noise.
- No database change. No new race condition.
- Rollback: revert the change. Prod behaves as today while `OTEL_SDK_DISABLED` stays `true`, except that `/actuator/prometheus` exists and responses carry `X-Trace-Id`.
