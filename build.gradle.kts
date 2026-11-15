plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	id("org.springframework.boot") version "3.5.16"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.graalvm.buildtools.native") version "0.10.6"
}

group = "com.kkpa"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

// Spring Cloud 2025.0 is the release train for Spring Boot 3.5.
extra["springCloudVersion"] = "2025.0.0"

dependencies {
	implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
	implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j")
	// Spring Cloud Gateway 4.3 renamed the starter. Routes live under spring.cloud.gateway.server.webflux.
	implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
	implementation("org.springframework.cloud:spring-cloud-starter-consul-discovery")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	// Observability. Versions come from the Spring Boot BOM. micrometer-tracing already brings context-propagation.
	implementation("io.micrometer:micrometer-tracing-bridge-otel")
	implementation("io.opentelemetry:opentelemetry-exporter-otlp")
	implementation("io.micrometer:micrometer-registry-prometheus")
	// Pinned: the Boot BOM does not manage it. 2.15.0-alpha is built for OpenTelemetry SDK 1.49.0, the BOM version.
	implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.15.0-alpha")
	implementation("com.github.ben-manes.caffeine:caffeine")
	implementation("io.jsonwebtoken:jjwt-api:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("io.projectreactor:reactor-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
