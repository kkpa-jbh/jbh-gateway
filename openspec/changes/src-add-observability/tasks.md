## 0. Test blueprint

No test blueprint group. The JBH-55 plan excludes unit tests and this change has no unit-testable logic (see design.md "Test contract").

## 1. Libraries and config (owner: src worker)

- [x] 1.1 `build.gradle.kts`: add `io.micrometer:micrometer-tracing-bridge-otel`, `io.opentelemetry:opentelemetry-exporter-otlp`, `io.micrometer:micrometer-registry-prometheus` (BOM versions) and `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.15.0-alpha` (pinned, confirm against the resolved POM, design D14). Add `io.micrometer:context-propagation` only if it is not already on the classpath (D11). Depends on: none.
- [x] 1.2 `src/main/resources/application.yml`: add, as in design D1 to D11: the export gate lookup (D2); `management.otlp.{tracing,logging}` endpoint, transport `grpc`, `export.enabled`; `management.tracing.sampling.probability`, `management.tracing.baggage.enabled: false`; `management.endpoints.web.exposure.include: health,info,prometheus`; `management.metrics.tags` and `management.metrics.distribution.slo` (whole `ms` values); `management.opentelemetry.resource-attributes`; `logging.structured.ecs.service.*`; `spring.reactor.context-propagation: auto`; under `spring.cloud.gateway.server.webflux`: `metrics.enabled: true`, `observability.enabled: true`, `default-filters: [RemoveRequestHeader=baggage]` with a YAML comment for the reason (D6). Do not change routes. After the edit, check that no route predicate can match `/actuator/**` (D15). Depends on: none (YAML only; the new properties are inert until 1.1 puts the libraries on the classpath).

## 2. Code and log file (owner: src worker)

The four tasks touch different files. They run in parallel once 1.1 is done.

- [x] 2.1 `src/main/kotlin/com/kkpa/jbh_gateway/filter/TraceIdResponseFilter.kt`: `WebFilter`, order `Ordered.HIGHEST_PRECEDENCE`; registers `response.beforeCommit` that sets `X-Trace-Id` from the server observation context (D5); no header when no trace id; never throws. Depends on: 1.1.
- [x] 2.2 `src/main/kotlin/com/kkpa/jbh_gateway/config/OpenTelemetryAppenderConfig.kt`: calls `OpenTelemetryAppender.install(openTelemetry)` at bean start (D13). Depends on: 1.1.
- [x] 2.3 `src/main/kotlin/com/kkpa/jbh_gateway/observation/` one `ObservationFilter` bean that replaces the gateway client attribute `http.uri` with the URI without query (D9). Confirm the `GatewayContext` API in the jar first. Depends on: 1.1.
- [x] 2.4 `src/main/resources/logback-spring.xml`: console text for non-prod, ECS JSON for `prod`, `OpenTelemetryAppender` on root in every profile, `additivity="false"` console-only loggers for `io.opentelemetry`, `io.grpc`, `okhttp3`, `okio` (D12). Depends on: 1.1.

## 3. Verification

- [x] 3.1 Static review by the worker (no command run): no route matches `/actuator/**`; no new log statement writes a token, cookie, `Authorization` or `X-Forwarded-For`; every new function is far below cyclomatic 10 and cognitive 15; file sizes. Depends on: 1.2, 2.1, 2.2, 2.3, 2.4.
- [x] 3.2 Leader runs the build and the existing suite (`./gradlew build`, `./gradlew test`; `JbhGatewayApplicationTests` must stay green) and the complexity check (`detekt --input src/main/kotlin --config config/detekt/complexity.yml`). Depends on: 3.1. Owner: leader.
- [ ] 3.3 User runs the manual checks M1 to M14 from design.md "Test contract" and reports the series count (M14). Depends on: 3.2.

## 4. Docs (leader)

Root docs are leader-owned. These tasks run in parallel with each other and with group 2.

- [x] 4.1 `CLAUDE.md` and `README.md`: new "Observability" section: signals (trace, Prometheus metrics, OTLP logs, `X-Trace-Id`), env vars (`OTEL_SDK_DISABLED`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_TRACES_SAMPLER_ARG`, `JBH_ENV`, `JBH_SERVICE_VERSION`), the local check (M1, M6, M11, M12), the export gate rule (D1), and the security rule: no route may match `/actuator/**`; `/actuator/prometheus` has no JWT and is reachable only from Docker networks because Caddy forwards only `/jbh-api/*`. Also update the "Actuator Endpoints" and "Code Structure" lines. Depends on: none. Owner: leader.
- [ ] 4.2 `../jbh-deploy/README.md`, "Frontend to gateway": one line that every gateway response carries `X-Trace-Id` (the W3C trace id). Commit it in the `jbh-deploy` repo as a separate commit. Depends on: none. Owner: leader.

Size estimate: 13 files, ~650 changed lines (tests and docs included)
