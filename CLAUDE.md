# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

JBH Gateway is a reactive API Gateway built with Spring Cloud Gateway and Kotlin.

It serves as the entry point for microservices, providing routing, rate limiting, circuit breaking, and authorization.

**Key Technologies:**
- Kotlin with Spring Boot 3.3.4
- Spring Cloud Gateway (reactive/non-blocking)
- Consul for service discovery
- Resilience4J for circuit breaking
- Caffeine for in-memory caching
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
- `/jbh-api/notifications/**` → `jbh-personal-finance` (protected; notifications is a module inside the finance app, not a separate service)

### Security Model

**Authorization is handled by a GlobalFilter** (class `AuthorizationGlobalFilter` in `filter/AuthorizationHeaderFilter.kt`, order -1):
- Public paths: only `/jbh-api/auth/**` (the `publicPaths` list). **Every other path is protected by default.**
- Token is read in this order: `Authorization: Bearer <token>` header, then cookie `JBH_TOKEN` (local), then cookie `__Secure-JBH_TOKEN` (prod).
- `JwtValidator` checks the signature (shared `jwt.secret` / env `JWT_SECRET`, same as `jbh-iam`), expiration, the `token_type` claim (`ACCESS`), and a non-blank `sub` (user id).
- On success, the user id is stored in the exchange attribute `userId`.
- On failure, it returns `401` with a JSON body (`error_code`, `message`) from `AuthErrorCode`.

### Rate Limiting

**Custom in-memory rate limiter** (`InMemoryRateLimiter`):
- 10 requests per second per IP address
- 1-second sliding window with atomic counters
- Caffeine cache with 1-minute expiration
- No Redis dependency
- Applied to all routes via `RequestRateLimiter` filter
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
- `application.yml` - Gateway routes, Consul config, and filter configuration

## Development Notes

**Reactive Programming:**
- All components use Project Reactor (`Mono`/`Flux`)
- Filters must return `Mono<Void>` and avoid blocking operations
- Use `reactor-test` for testing reactive flows

**Adding New Routes:**
1. Add route configuration in `application.yml` under `spring.cloud.gateway.routes`
2. Use `lb://service-name` for load-balanced Consul services
3. Apply rate limiting via `RequestRateLimiter` filter
4. New routes are protected by default. Add the path to `publicPaths` in `AuthorizationGlobalFilter` only if it must be public

**Actuator Endpoints:**
Available at `/actuator/`: `health`, `info`, `metrics`, `gateway`

## System map sync (`../README.md`)

This repo is one part of the JBH system. The map of how all JBH services talk to each other
lives in `../README.md` (relative to this repo's root: the `kkpa-jbh` folder that holds all JBH repos).

**Rule:** when a change in this repo affects how services communicate, update `../README.md`
in the same task. Change only the rows or lines that are affected. Then tell the user that
`../README.md` changed, because that folder is not a git repo and the change is not versioned.

Triggers for this repo:
- Add, remove, or change a route in `application.yml` (path, `lb://` target)
- Change `publicPaths` or the JWT checks in `AuthorizationGlobalFilter` / `JwtValidator`
- Change the port, the Consul service name, the JWT secret env, the rate limit, or CORS

If you are not sure a change counts, read `../README.md` and check whether any line is now wrong.
