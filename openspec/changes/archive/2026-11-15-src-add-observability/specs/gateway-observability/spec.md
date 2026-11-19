## ADDED Requirements

### Requirement: Trace id on every response
The gateway SHALL add the header `X-Trace-Id` with the W3C trace id (32 lowercase hex characters) of the current request to every response. This includes `401` from the authorization filter, `429` from the rate limiter, `404` when no route matches, `5xx` errors, and actuator responses. The header SHALL appear once. The gateway MUST NOT fail a request when no trace id is available; it then sends no header.

#### Scenario: Normal routed response
- **WHEN** a client sends a request to a routed path
- **THEN** the response has one `X-Trace-Id` header with 32 hex characters

#### Scenario: Request without a token
- **WHEN** a client sends a request without a token to a protected path
- **THEN** the gateway returns `401` and the response has `X-Trace-Id`

#### Scenario: Rate limit exceeded
- **WHEN** one IP sends more requests than the limit allows
- **THEN** the gateway returns `429` and the response has `X-Trace-Id`

#### Scenario: No route matches
- **WHEN** a client sends a request to a path that no route matches
- **THEN** the gateway returns `404` and the response has `X-Trace-Id`

#### Scenario: Incoming traceparent is continued
- **WHEN** a client sends the header `traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01`
- **THEN** the response header is `X-Trace-Id: 0af7651916cd43dd8448eb211c80319c`

### Requirement: Trace context to services
The gateway SHALL continue an incoming `traceparent`, or start a new trace when none comes in. It SHALL send `traceparent` on every routed call to a service. It SHALL NOT send an incoming `baggage` header to a service, and SHALL NOT read baggage into the trace.

#### Scenario: Routed call carries traceparent
- **WHEN** the gateway routes a request to a service
- **THEN** the call to the service has a `traceparent` header with the same trace id as `X-Trace-Id`

#### Scenario: Baggage from the internet is dropped
- **WHEN** a client sends `baggage: k=v` on a routed request
- **THEN** the call to the service has no `baggage` header

### Requirement: Export gate by OTEL_SDK_DISABLED
The gateway SHALL send traces and logs by OTLP (gRPC) only when the env var `OTEL_SDK_DISABLED` is exactly `false`. For an unset, empty or any other value it SHALL send nothing and SHALL start normally. The value of `OTEL_SDK_DISABLED` SHALL NOT turn off trace ids, `X-Trace-Id`, the `traceId`/`spanId` fields in logs, or the downstream `traceparent`. The endpoint SHALL come from `OTEL_EXPORTER_OTLP_ENDPOINT` (default `http://localhost:14317`). The sampling probability SHALL come from `OTEL_TRACES_SAMPLER_ARG` (default `1.0`).

#### Scenario: Default is export off
- **WHEN** the gateway starts with `OTEL_SDK_DISABLED` unset
- **THEN** it opens no connection to a collector
- **AND** responses still carry `X-Trace-Id`

#### Scenario: Unexpected value
- **WHEN** the gateway starts with `OTEL_SDK_DISABLED=banana`
- **THEN** it starts and export is off

#### Scenario: Export on
- **WHEN** the gateway starts with `OTEL_SDK_DISABLED=false`
- **THEN** it sends spans and log records to `OTEL_EXPORTER_OTLP_ENDPOINT`
- **AND** the spans have the resource attributes `service.name=jbh-gateway`, `service.namespace=jbh`, `deployment.environment.name` (from `JBH_ENV`, default `local`) and `service.version` (from `JBH_SERVICE_VERSION`, default `unknown`)

#### Scenario: Collector is missing
- **WHEN** export is on and the collector is not reachable
- **THEN** the gateway starts, `/actuator/health` is `UP`, and requests are not delayed
- **AND** the log does not fill with repeated exporter errors
- **AND** exporter error logs are not sent as OTLP logs again

### Requirement: Prometheus metrics
The gateway SHALL expose `/actuator/prometheus`. Every metric SHALL have the tags `service=jbh-gateway` and `env` (from `JBH_ENV`, default `local`). The meters `http.server.requests` and `spring.cloud.gateway.requests` SHALL publish a histogram with exactly these bucket bounds: 5 ms, 10 ms, 25 ms, 50 ms, 100 ms, 250 ms, 500 ms, 1 s, 2.5 s, 5 s, 10 s. The meter `spring.cloud.gateway.requests` SHALL have the tag `routeId`. No metric tag SHALL hold a user id, an email, a client IP, a query string, or a raw path.

#### Scenario: Histogram buckets
- **WHEN** a client reads `/actuator/prometheus` after some traffic
- **THEN** `http_server_requests_seconds_bucket` and `spring_cloud_gateway_requests_seconds_bucket` show the `le` values `0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, +Inf`

#### Scenario: Route tag and common tags
- **WHEN** a client sends a request through the route `auth_api_route` and then reads `/actuator/prometheus`
- **THEN** a `spring_cloud_gateway_requests_seconds_count` series has `routeId="auth_api_route"`, `service="jbh-gateway"` and `env="local"`

#### Scenario: No personal data in labels
- **WHEN** a client sends `/jbh-api/auth/checkEmailAvailability?email=x@y.z` and then reads `/actuator/prometheus`
- **THEN** the text `x@y.z` does not appear in the output

### Requirement: Log correlation and OTLP logs
Every application log line SHALL carry the trace id and the span id of its request, also lines written from reactive code. Outside the `prod` profile the console output SHALL be text with `traceId` and `spanId`. In the `prod` profile the console output SHALL be JSON in the Elastic Common Schema (Spring Boot structured logging) with the trace id and the span id. The OpenTelemetry log appender SHALL be active in every profile and SHALL send only the log message, level and logger name, with trace context, and no MDC or key-value attributes.

#### Scenario: Text log in local profile
- **WHEN** the gateway handles a request in a non-prod profile
- **THEN** a log line for that request shows the same trace id as the `X-Trace-Id` response header

#### Scenario: JSON log in prod profile
- **WHEN** the gateway handles a request with `SPRING_PROFILES_ACTIVE=prod`
- **THEN** stdout shows one JSON object per log line and it contains the trace id

#### Scenario: Logs in Loki
- **WHEN** export is on and a request is handled
- **THEN** a log record with the trace id of that request reaches the collector

### Requirement: Telemetry privacy and actuator exposure
No route SHALL match `/actuator/**`. `/actuator/prometheus` SHALL need no JWT, because it is not a routed request. No JWT, cookie or `Authorization` value SHALL appear in logs, metric tags or span attributes. The query string of a request SHALL NOT appear in a span attribute. The rate limiter SHALL NOT log the `X-Forwarded-For` header.

#### Scenario: Actuator is not routed
- **WHEN** the route definitions in `application.yml` are read
- **THEN** every route predicate is a `Path=/jbh-api/...` predicate and no route can match `/actuator/**`

#### Scenario: Prometheus endpoint needs no token
- **WHEN** a client in the Docker network sends `GET /actuator/prometheus` without a token
- **THEN** the gateway returns `200`

#### Scenario: Query string not in a span
- **WHEN** export is on and a request with `?email=x@y.z` is routed
- **THEN** no exported span attribute contains `x@y.z`
