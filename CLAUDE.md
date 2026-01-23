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
./gradlew bootRun              # Start the gateway (port 8080)
./gradlew build                # Build JAR
./gradlew test                 # Run tests
```

**Docker:**
```bash
./gradlew bootBuildImage       # Build container image
docker run --rm -p 8080:8080 jbh-gateway:0.0.1-SNAPSHOT
```

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
- `/jbh-api/users/**` → `jbh-iam-service` (protected)
- `/jbh-api/products/**` → `jbh-personal-finance` (protected)
- `/jbh-api/notifications/**` → `jbh-notifications-service` (protected)

### Security Model

**Authorization is handled by a GlobalFilter** (`AuthorizationGlobalFilter` at order -1):
- Public routes: `/jbh-api/auth/**` (no token required)
- Protected routes: `/jbh-api/users/**`, `/jbh-api/products/**`, `/jbh-api/notifications/**`
- Protected routes require `Authorization: Bearer <token>` header
- The filter validates token presence but does NOT validate token content (It's pending to implement it)

### Rate Limiting

**Custom in-memory rate limiter** (`InMemoryRateLimiter`):
- 10 requests per second per IP address
- 1-second sliding window with atomic counters
- Caffeine cache with 1-minute expiration
- No Redis dependency
- Applied to all routes via `RequestRateLimiter` filter
- IP-based keying via `ipKeyResolver`

### Service Discovery

Consul integration:
- Host: `localhost:8500`
- Health check: `/actuator/health` every 60s
- Instance ID includes random value for uniqueness

## Code Structure

- `JbhGatewayApplication.kt` - Main Spring Boot application
- `filter/AuthorizationGlobalFilter.kt` - Global auth filter for protected routes
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
4. Add path to `protectedPaths` in `AuthorizationGlobalFilter` if route requires auth

**Actuator Endpoints:**
Available at `/actuator/`: `health`, `info`, `metrics`, `gateway`
