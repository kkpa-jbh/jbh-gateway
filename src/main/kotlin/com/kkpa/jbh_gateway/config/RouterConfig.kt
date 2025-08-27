package com.kkpa.jbh_gateway.config

import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RouteConfig {

    @Bean
    fun customRouteLocator(builder: RouteLocatorBuilder): RouteLocator = builder.routes()
        .route("default_route") { r ->
            r.path("/")
                .uri("lb://jbh-iam-service")
        }
        .route("jbh_view_route") { r ->
            r.path("/jbh/view/**")
                .uri("lb://jbh-iam-service")
        }
        .route("jbh_iam_route") { r ->
            r.path("/jbh/**")
                .uri("lb://jbh-iam-service")
        }
        .build()

}