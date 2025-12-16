package com.kkpa.jbh_gateway.filter

import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class AuthorizationGlobalFilter : GlobalFilter, Ordered {

    private val protectedPaths = listOf("/jbh-api/users", "/jbh-api/accounts", "/jbh-api/notifications")

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val request = exchange.request
        val path = request.uri.path

        val requiresAuth = protectedPaths.any { path.startsWith(it) }

        if (!requiresAuth) {
            return chain.filter(exchange)
        }

        val authHeader = request.headers.getFirst("Authorization")

        if (authHeader.isNullOrBlank() || !authHeader.startsWith("Bearer ")) {
            val response = exchange.response
            response.statusCode = HttpStatus.UNAUTHORIZED
            return response.setComplete()
        }

        val token = authHeader.substring(7).trim()
        if (token.isEmpty()) {
            val response = exchange.response
            response.statusCode = HttpStatus.UNAUTHORIZED
            return response.setComplete()
        }

        return chain.filter(exchange)
    }

    override fun getOrder(): Int = -1
}