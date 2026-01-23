# JBH Gateway

A reactive API Gateway built with Spring Cloud Gateway and Kotlin, providing routing, load balancing, and resilience patterns for microservices architecture.

## Overview

JBH Gateway serves as the entry point for client requests in a microservices ecosystem. It routes incoming HTTP requests to appropriate backend services while providing cross-cutting concerns like authentication, rate limiting, circuit breaking, and request/response transformation.

## Features

- **Reactive Architecture**: Built on Spring WebFlux and Project Reactor for high-performance, non-blocking request handling
- **Dynamic Routing**: Flexible request routing based on path, headers, query parameters, and more
- **Circuit Breaker**: Resilience4J integration for fault tolerance and cascading failure prevention
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

Gateway routes and filters are configured in `application.properties` or `application.yml`. Example configuration:

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: user-service
          uri: http://localhost:8081
          predicates:
            - Path=/api/users/**
          filters:
            - StripPrefix=2
            - name: CircuitBreaker
              args:
                name: user-service
                fallbackUri: forward:/fallback/users
```

## Architecture

```
Client → JBH Gateway → [Service A, Service B, Service C, ...]
```

The gateway acts as a reverse proxy, routing requests to backend services based on configured rules while providing:

- **Security**: Authentication and authorization
- **Reliability**: Circuit breakers and retry mechanisms  
- **Performance**: Caching and rate limiting
- **Observability**: Logging, metrics, and tracing

## Development

### Project Structure

```
src/
├── main/
│   ├── kotlin/com/kkpa/jbh_gateway/
│   │   └── JbhGatewayApplication.kt
│   └── resources/
│       └── application.properties
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

**Protected Routes** (require valid JWT token):
- `/jbh-api/users/**`
- `/jbh-api/products/**`
- `/jbh-api/notifications/**`

**Public Routes** (no token required):
- `/jbh-api/auth/**`

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
- 1-second sliding window with automatic cleanup
- No Redis dependency - completely self-contained
- Caffeine cache for high-performance in-memory storage