package com.kkpa.jbh_gateway.config

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import org.springframework.beans.factory.InitializingBean
import org.springframework.stereotype.Component

/**
 * Connects the Logback `OpenTelemetryAppender` (declared in logback-spring.xml) to the Spring
 * `OpenTelemetry` bean. Until this runs the appender buffers events, then it replays them.
 * Calling `install` again (several test contexts in one JVM) is safe.
 */
@Component
class OpenTelemetryAppenderConfig(
    private val openTelemetry: OpenTelemetry
) : InitializingBean {

    override fun afterPropertiesSet() {
        OpenTelemetryAppender.install(openTelemetry)
    }
}
