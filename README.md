# JBH Gateway

A reactive API Gateway built with Spring Cloud Gateway and Kotlin, providing routing, load balancing, and resilience patterns for microservices architecture.

## Overview

JBH Gateway serves as the entry point for client requests in a microservices ecosystem. It routes incoming HTTP requests to appropriate backend services while providing cross-cutting concerns like authentication, rate limiting, circuit breaking, and request/response transformation.

## Features

- **Reactive Architecture**: Built on Spring WebFlux and Project Reactor for high-performance, non-blocking request handling
- **Dynamic Routing**: Flexible request routing based on path, headers, query parameters, and more
- **Circuit Breaker**: Resilience4J dependency is included (not configured on any route yet)
- **Load Balancing**: Distribute requests across multiple service instances
- **Request/Response Filtering**: Pre and post-processing of requests and responses
- **Metrics & Monitoring**: Built-in observability for gateway performance tracking
- **Native Compilation**: GraalVM native image support for fast startup and low memory footprint

## Technology Stack

- **Kotlin** - Primary programming language
- **Spring Boot 3.3.4** - Application framework
- **Spring Cloud Gateway** - API Gateway implementation
- **Project Reactor** - Reactive programming model
- **Resilience4J** - Circuit breaker and fault tolerance
- **Gradle** - Build automation tool
- **Java 21** - Runtime platform
- **GraalVM** - Optional native compilation

## Getting Started

### Prerequisites

- Java 21 or higher
- Gradle 7.x+ (or use included wrapper)
- Docker (optional, for containerization)

### Running the Application

1. **Start the gateway**:
   ```bash
   ./gradlew bootRun
   ```

2. **Build JAR**:
   ```bash
   ./gradlew build
   ```

3. **Run JAR**:
   ```bash
   java -jar build/libs/jbh-gateway-0.0.1-SNAPSHOT.jar
   ```

### Docker Support

**Build container image**:
```bash
./gradlew bootBuildImage
```

**Run container**:
```bash
docker run --rm -p 8080:8080 jbh-gateway:0.0.1-SNAPSHOT
```

### Native Compilation (GraalVM)

**Build native executable**:
```bash
./gradlew nativeCompile
```

**Run native executable**:
```bash
./build/native/nativeCompile/jbh-gateway
```

## Configuration

Routes, Consul and JWT settings live in `src/main/resources/application.yml`.
Services are found in Consul by name with the `lb://` scheme.

| Path | Target (Consul name) |
|------|----------------------|
| `/jbh-api/auth/**` | `lb://jbh-iam-service` (public) |
| `/jbh-api/users/**`, `/jbh-api/user-groups/**` | `lb://jbh-iam-service` |
| `/jbh-api/finance/**`, `/jbh-api/products/**`, `/jbh-api/preferences/**` | `lb://jbh-personal-finance` |
| `/jbh-api/notifications/**` | `lb://jbh-personal-finance` |

Every route uses the `RequestRateLimiter` filter (`inMemoryRateLimiter` + `ipKeyResolver`).

Main settings:
- `server.port`: env `JBH_GATEWAY_SERVER_PORT` (default 8080). If you change it, also set `JBH_GATEWAY_URL` in `jbh-iam` and `jbh-personal-finance`.
- `spring.cloud.consul.host` / `port`: env `CONSUL_HOST` / `CONSUL_PORT` (default `localhost:8500`)
- `spring.cloud.consul.discovery.hostname`: env `CONSUL_DISCOVERY_HOSTNAME` (default `localhost`; Docker: `jbh-gateway`)
- `jbh.rate-limit.trusted-proxies`: env `JBH_TRUSTED_PROXIES` (default empty: only loopback is trusted)
- `jwt.secret` (env `JWT_SECRET`): must be the same value as in `jbh-iam`

Note: the Resilience4J dependency is present, but no route uses a `CircuitBreaker` filter yet.

## Architecture

```
jbh-app → JBH Gateway (:8080) → jbh-iam (:9999)
                              → jbh-personal-finance (:7777)
```

See the `README.md` in the parent folder for the full system map.

## Development

### Project Structure

```
src/
├── main/
│   ├── kotlin/com/kkpa/jbh_gateway/
│   │   ├── JbhGatewayApplication.kt
│   │   ├── config/JwtProperties.kt          # jwt.* properties
│   │   ├── config/RateLimitConfig.kt        # IP key resolver + in-memory rate limiter
│   │   ├── filter/AuthorizationHeaderFilter.kt  # class AuthorizationGlobalFilter
│   │   ├── model/AuthErrorCode.kt           # 401 error codes
│   │   ├── model/AuthErrorResponse.kt       # 401 JSON body
│   │   └── service/JwtValidator.kt          # JWT validation
│   └── resources/
│       └── application.yml
└── test/
    └── kotlin/com/kkpa/jbh_gateway/
        └── JbhGatewayApplicationTests.kt
```

### Testing

```bash
./gradlew test
```

**Native image testing**:
```bash
./gradlew nativeTest
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make changes with appropriate tests
4. Submit a pull request

## License

[License information to be added]

## Authentication & Authorization

### Route Protection

**Public Routes** (no token required) - the `publicPaths` list in `AuthorizationGlobalFilter`:
- `/jbh-api/auth/**`

**Protected Routes**: every other path. New routes are protected by default.

### Token Extraction

The gateway extracts JWT tokens from multiple sources (in order of precedence):
1. **Authorization Header**: `Authorization: Bearer <token>`
2. **Local Cookie**: `JBH_TOKEN` (development)
3. **Secure Cookie**: `__Secure-JBH_TOKEN` (production)

### JWT Token Validation

The gateway validates:
- ✅ Token signature (HS512 algorithm)
- ✅ Token expiration (`exp` claim)
- ✅ Token type must be `ACCESS`
- ✅ Subject (`sub`) contains user ID

### Gateway Response Scenarios

| Scenario | HTTP Status | Response Body |
|----------|-------------|---------------|
| **Valid Token** | `200` (or backend response) | Backend service response |
| **Missing Token** | `401 Unauthorized` | `{"error_code": "AUTH_MISSING_TOKEN", "message": "Authorization token is required", "timestamp": 1734357890123}` |
| **Expired Token** | `401 Unauthorized` | `{"error_code": "AUTH_TOKEN_EXPIRED", "message": "Access token has expired", "timestamp": 1734357890123}` |
| **Invalid Signature** | `401 Unauthorized` | `{"error_code": "AUTH_SI", "message": "SI  failed", "timestamp": 1734357890123}` |
| **Wrong Token Type** | `401 Unauthorized` | `{"error_code": "AUTH_INVALID_TT", "message": "Invalid TT", "timestamp": 1734357890123}` |
| **Malformed Token** | `401 Unauthorized` | `{"error_code": "AUTH_INVALID_TOKEN", "message": "Invalid or malformed token", "timestamp": 1734357890123}` |
| **Public Route** | `200` (or backend response) | No token validation - passes through |

### Example Requests

**Successful request with Authorization header:**
```bash
curl -H "Authorization: Bearer eyJhbGc..." http://localhost:8080/jbh-api/users/profile
# Response: 200 OK with user profile data
```

**Successful request with cookies (browser):**
```bash
curl -b "JBH_TOKEN=eyJhbGc..." http://localhost:8080/jbh-api/products/123
# Response: 200 OK with account data
```

**Failed request - expired token:**
```bash
curl -H "Authorization: Bearer <expired-token>" http://localhost:8080/jbh-api/users/profile
# Response: 401 Unauthorized
# Body: {"error_code": "AUTH_TOKEN_EXPIRED", "message": "Access token has expired", "timestamp": 1734357890123}
```

**Public endpoint - no token needed:**
```bash
curl http://localhost:8080/jbh-api/auth/login
# Response: 200 OK - no token validation
```

## Features

### In-Memory Rate Limiting
- 10 requests per second per IP address
- Behind a proxy: the client IP is the last entry of `X-Forwarded-For`. It is trusted only when the caller is loopback
  or inside `JBH_TRUSTED_PROXIES` (comma-separated CIDRs). Production sets the `edge` Docker subnet `10.231.0.0/24`,
  where Magus's Caddy runs. Caddy replaces any `X-Forwarded-For` the client sends.
- 1-second sliding window with automatic cleanup
- No Redis dependency - completely self-contained
- Caffeine cache for high-performance in-memory storage