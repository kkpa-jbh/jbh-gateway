# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

JBH Gateway is a reactive API Gateway built with Spring Cloud Gateway and Kotlin.

It serves as the entry point for microservices, providing routing, rate limiting, circuit breaking, and authorization.

**Key Technologies:**
- Kotlin with Spring Boot 3.5.16, Spring Cloud 2025.0.0 (Gateway 4.3, starter `spring-cloud-starter-gateway-server-webflux`)
- Spring Cloud Gateway (reactive/non-blocking)
- Consul for service discovery
- Resilience4J for circuit breaking
- Caffeine for in-memory caching
- Micrometer Tracing (OpenTelemetry bridge, OTLP gRPC), Micrometer Prometheus registry, OTel Logback appender
- Java 21 with GraalVM native image support

## Common Commands

**Build and run:**
```bash
./gradlew bootRun              # Start the gateway (port: env JBH_GATEWAY_SERVER_PORT, default 8080)
./gradlew build                # Build JAR
./gradlew test                 # Run tests
```

**Docker:**
- `Dockerfile` copies the Boot jar that CI builds (`./gradlew bootJar`). `.github/workflows/image.yml` pushes
  `ghcr.io/kkpa-jbh/jbh-gateway:<git-sha>`. The deploy lives in the `jbh-deploy` repo.

**GraalVM native compilation:**
```bash
./gradlew nativeCompile        # Build native executable
./gradlew nativeTest           # Run tests with native image
./build/native/nativeCompile/jbh-gateway  # Run native executable
```

## Architecture

### Routing Strategy

The gateway routes requests to backend microservices via Consul service discovery using the `lb://` (load-balanced) URI scheme. All routes are defined in `application.yml`:

- `/jbh-api/auth/**` → `jbh-iam-service` (public)
- `/jbh-api/users/**`, `/jbh-api/user-groups/**` → `jbh-iam-service` (protected)
- `/jbh-api/finance/**`, `/jbh-api/products/**`, `/jbh-api/preferences/**` → `jbh-personal-finance` (protected)
- `/jbh-api/notifications/**` → `jbh-notification` (protected; its own service, no longer a module inside the finance app)

### Security Model

**Authorization is handled by a GlobalFilter** (class `AuthorizationGlobalFilter` in `filter/AuthorizationHeaderFilter.kt`, order -1):
- Public paths: only `/jbh-api/auth/**` (the `publicPaths` list). **Every other path is protected by default.**
- Token is read in this order: `Authorization: Bearer <token>` header, then cookie `JBH_TOKEN` (local), then cookie `__Secure-JBH_TOKEN` (prod).
- `JwtValidator` checks the signature (shared `jwt.secret` / env `JBH_JWT_SECRET`, required, no default, Base64, same as `jbh-iam`; key = `Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))`, HS256), expiration, the `token_type` claim (`ACCESS`), and a non-blank `sub` (user id).
- On success, the user id is stored in the exchange attribute `userId`.
- On failure, it returns `401` with a JSON body (`error_code`, `message`) from `AuthErrorCode`.

### Rate Limiting

**Custom in-memory rate limiter** (`InMemoryRateLimiter`):
- `InMemoryRateLimiter(maxRequests, windowMillis)`: fixed window per IP, count and reset inside `synchronized`
- Bean `inMemoryRateLimiter` (`@Primary`, the default): 10 requests per second. Used by all routes
- Bean `authRateLimiter`: 10 requests per minute. Used by route `auth_login_route` (`/jbh-api/auth/signin`, `/signup`, `/google`), declared before `auth_api_route` because the first matching route wins
- Caffeine cache, entries expire after 2 idle windows
- No Redis dependency
- IP-based keying via `ipKeyResolver`. It reads the last entry of `X-Forwarded-For`, but only when the direct caller is loopback or inside `jbh.rate-limit.trusted-proxies` (env `JBH_TRUSTED_PROXIES`, comma-separated CIDRs; production: the `edge` subnet `10.231.0.0/24`, where Magus's Caddy lives). Otherwise it uses the remote address, so clients cannot fake the header.

### Service Discovery

Consul integration:
- Host: env `CONSUL_HOST` (default `localhost`), port `CONSUL_PORT` (default 8500)
- Registered hostname: env `CONSUL_DISCOVERY_HOSTNAME` (default `localhost`; Docker: `jbh-gateway`)
- Health check: `/actuator/health` every 60s
- Instance ID includes random value for uniqueness

## Code Structure

- `JbhGatewayApplication.kt` - Main Spring Boot application
- `filter/AuthorizationHeaderFilter.kt` - Global auth filter (class `AuthorizationGlobalFilter`)
- `service/JwtValidator.kt` - JWT validation, returns `Valid(userId)` or `Invalid(errorCode)`
- `config/JwtProperties.kt` - `jwt.*` properties (secret, token type claim)
- `model/AuthErrorCode.kt`, `model/AuthErrorResponse.kt` - 401 error codes and body
- `config/RateLimitConfig.kt` - Rate limiter beans (IP resolver + in-memory limiter)
- `config/OpenTelemetryAppenderConfig.kt` - Installs the OTel Logback appender with the Spring `OpenTelemetry` bean
- `filter/TraceIdResponseFilter.kt` - `WebFilter` that sets `X-Trace-Id` on every response
- `observation/GatewayClientUriObservationFilter.kt` - Removes the query string from the client span `http.uri`
- `application.yml` - Gateway routes, Consul config, filter configuration, tracing / metrics / OTLP config
- `logback-spring.xml` - Text logs (non-prod) or ECS JSON (`prod`), plus the OTel appender

## Development Notes

**Reactive Programming:**
- All components use Project Reactor (`Mono`/`Flux`)
- Filters must return `Mono<Void>` and avoid blocking operations
- Use `reactor-test` for testing reactive flows

**Adding New Routes:**
1. Add route configuration in `application.yml` under `spring.cloud.gateway.server.webflux.routes` (the Gateway 4.3 prefix; the old `spring.cloud.gateway.routes` is deprecated)
2. Use `lb://service-name` for load-balanced Consul services
3. Apply rate limiting via `RequestRateLimiter` filter
4. New routes are protected by default. Add the path to `publicPaths` in `AuthorizationGlobalFilter` only if it must be public

**Actuator Endpoints:**
Available at `/actuator/`: `health`, `info`, `prometheus` only.
**No route may match `/actuator/**`.** `/actuator/prometheus` has no JWT check (the auth GlobalFilter runs only on
routed requests). In production it is reachable only from Docker networks, because Caddy forwards only `/jbh-api/*`.

## Observability

Details, env vars and the local check: `README.md`, "Observability". System view: `../jbh-deploy/observability/README.md`.

- **Traces:** Micrometer Tracing + OpenTelemetry bridge. The gateway continues an incoming `traceparent` or starts a
  new trace; routed calls get a client span and send `traceparent` (Gateway observability). Sampling: `OTEL_TRACES_SAMPLER_ARG` (default `1.0`).
- **Export gate:** `OTEL_SDK_DISABLED` (default `true`) stops only the OTLP export (`management.otlp.{tracing,logging}.export.enabled`,
  through the lookup table `jbh.otel.*` in `application.yml`). Only the exact value `false` turns it on. Never map it to
  `management.tracing.enabled`: that removes the trace id, `X-Trace-Id` and `traceparent`.
- **OTLP:** gRPC, `OTEL_EXPORTER_OTLP_ENDPOINT` (default `http://localhost:14317`; Docker `http://jbh-alloy:4317`).
- **Metrics:** `/actuator/prometheus`, tags `service=jbh-gateway`, `env=${JBH_ENV}`. Route latency: `spring.cloud.gateway.requests`
  (tag `routeId`). SLO buckets must be whole `ms` values (`2500ms`, not `2.5s`: Micrometer rejects it and the gateway does not start).
- **Logs:** `logback-spring.xml`: text with `traceId`/`spanId` except profile `prod` (ECS JSON on stdout); the OpenTelemetry
  Logback appender in every profile. Exporter loggers (`io.opentelemetry`, `io.grpc`, ...) never reach that appender (no loop).
- **`X-Trace-Id`:** a `WebFilter` at highest precedence sets it in `beforeCommit`, from the server observation context
  (not the `Tracer` bean: no span exists yet when `filter()` runs). So 401, 404, 429 and 5xx get it too.
- **Privacy:** incoming `baggage` is dropped by config; the client span attribute `http.uri` has no query string.
  Never log or tag a token, cookie, `Authorization`, email, IP or raw path.

## Pipeline config (kkpa-orchestrate-change)

- **Modules-Root:** `.` (one module: `src`. Root docs like `README.md` and `CLAUDE.md` are leader-owned)
- **Doc-Pack:** `CLAUDE.md`
- **Complexity-Check:** `detekt --input src/main/kotlin --config config/detekt/complexity.yml` (detekt CLI, `brew install detekt`)
- **Design-Gates:** `database`
- **Git-Strategy:** `auto`

## System map sync (`../jbh-deploy/README.md`)

This repo is one part of the JBH system. The map of how all JBH services talk to each other
lives in `../jbh-deploy/README.md` (the `jbh-deploy` repo, a sibling of this one in the `kkpa-jbh` folder).

**Rule:** when a change in this repo affects how services communicate, update `../jbh-deploy/README.md`
in the same task. Change only the rows or lines that are affected. Then commit it in the
`jbh-deploy` repo (a separate commit there; never one commit across repos).

Triggers for this repo:
- Add, remove, or change a route in `application.yml` (path, `lb://` target)
- Change `publicPaths` or the JWT checks in `AuthorizationGlobalFilter` / `JwtValidator`
- Change the port, the Consul service name, the JWT secret env, the rate limit, or CORS
- Change a response header every client sees (`X-Trace-Id`), or the OTLP / metrics env vars

If you are not sure a change counts, read `../jbh-deploy/README.md` and check whether any line is now wrong.
