package com.kkpa.jbh_gateway.filter

import com.fasterxml.jackson.databind.ObjectMapper
import com.kkpa.jbh_gateway.model.AuthErrorCode
import com.kkpa.jbh_gateway.model.AuthErrorResponse
import com.kkpa.jbh_gateway.service.JwtValidator
import org.jboss.logging.Logger
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class AuthorizationGlobalFilter(
    private val jwtValidator: JwtValidator,
    private val objectMapper: ObjectMapper
) : GlobalFilter, Ordered {

    companion object {
        private val LOG: Logger = Logger.getLogger(AuthorizationGlobalFilter::class.java)
        const val USER_ID_ATTRIBUTE = "userId"
        private const val LOCAL_COOKIE_NAME = "JBH_TOKEN"
        private const val SECURE_COOKIE_NAME = "__Secure-JBH_TOKEN"
    }

    private val publicPaths = listOf("/jbh-api/auth/")

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val request = exchange.request
        val path = request.uri.path
        val method = request.method
        val clientIp = request.remoteAddress?.address?.hostAddress ?: "unknown"

        val isPublicJBHAPI = publicPaths.any { path.startsWith(it) }

        LOG.debugf("Incoming request: method=%s, path=%s, clientIp=%s, requiresAuth=%s", method, path, clientIp, !isPublicJBHAPI)

        if (isPublicJBHAPI) {
            LOG.debugf("Public route accessed: %s %s", method, path)
            return chain.filter(exchange)
        }

        val token = extractToken(exchange)

        if (token == null) {
            LOG.warnf("Missing token for protected route: %s %s from clientIp=%s", method, path, clientIp)
            return writeErrorResponse(exchange, AuthErrorCode.MISSING_TOKEN)
        }

        return when (val result = jwtValidator.validateToken(token)) {
            is JwtValidator.ValidationResult.Valid -> {
                exchange.attributes[USER_ID_ATTRIBUTE] = result.userId
                LOG.debugf("Authorized request: %s %s, userId=%s, clientIp=%s", method, path, result.userId, clientIp)
                chain.filter(exchange)
            }
            is JwtValidator.ValidationResult.Invalid -> {
                LOG.warnf("Invalid token for: %s %s, errorCode=%s, clientIp=%s", method, path, result.errorCode.code, clientIp)
                writeErrorResponse(exchange, result.errorCode)
            }
        }
    }

    private fun extractToken(exchange: ServerWebExchange): String? {
        val authHeader = exchange.request.headers.getFirst("Authorization")
        if (!authHeader.isNullOrBlank() && authHeader.startsWith("Bearer ")) {
            val token = authHeader.substring(7).trim()
            if (token.isNotEmpty()) {
                return token
            }
        }

        val cookies = exchange.request.cookies
        val localCookie = cookies.getFirst(LOCAL_COOKIE_NAME)?.value
        if (!localCookie.isNullOrBlank()) {
            return localCookie
        }

        val secureCookie = cookies.getFirst(SECURE_COOKIE_NAME)?.value
        if (!secureCookie.isNullOrBlank()) {
            return secureCookie
        }

        return null
    }

    private fun writeErrorResponse(exchange: ServerWebExchange, errorCode: AuthErrorCode): Mono<Void> {
        val response = exchange.response
        response.statusCode = HttpStatus.UNAUTHORIZED
        response.headers.contentType = MediaType.APPLICATION_JSON

        val errorResponse = AuthErrorResponse(
            errorCode = errorCode.code,
            message = errorCode.message
        )

        val bytes = objectMapper.writeValueAsBytes(errorResponse)
        val buffer: DataBuffer = response.bufferFactory().wrap(bytes)

        return response.writeWith(Mono.just(buffer))
    }

    override fun getOrder(): Int = -1
}