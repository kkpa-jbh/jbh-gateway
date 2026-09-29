package com.kkpa.jbh_gateway.filter

import com.fasterxml.jackson.databind.ObjectMapper
import com.kkpa.jbh_gateway.TestTokens
import com.kkpa.jbh_gateway.service.JwtValidator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.http.HttpCookie
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono

class AuthorizationGlobalFilterTest {

    private val filter = AuthorizationGlobalFilter(JwtValidator(TestTokens.properties()), ObjectMapper())

    private var chainCalled = false
    private val chain = GatewayFilterChain { chainCalled = true; Mono.empty() }

    private fun run(request: MockServerHttpRequest.BaseBuilder<*>): MockServerWebExchange {
        val exchange = MockServerWebExchange.from(request)
        filter.filter(exchange, chain).block()
        return exchange
    }

    @Test
    fun `public auth path passes without a token`() {
        run(MockServerHttpRequest.post("/jbh-api/auth/signin"))

        assertThat(chainCalled).isTrue()
    }

    @Test
    fun `protected path without a token gets 401 MISSING_TOKEN`() {
        val exchange = run(MockServerHttpRequest.get("/jbh-api/finance/products"))

        assertThat(chainCalled).isFalse()
        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(exchange.response.bodyAsString.block()).contains("AUTH_MISSING_TOKEN")
    }

    @Test
    fun `valid bearer token passes and sets the user id`() {
        val exchange = run(
            MockServerHttpRequest.get("/jbh-api/finance/products")
                .header("Authorization", "Bearer ${TestTokens.token()}")
        )

        assertThat(chainCalled).isTrue()
        assertThat(exchange.getAttribute<String>(AuthorizationGlobalFilter.USER_ID_ATTRIBUTE)).isEqualTo(TestTokens.USER_ID)
    }

    @Test
    fun `valid token in the secure cookie passes`() {
        run(
            MockServerHttpRequest.get("/jbh-api/finance/products")
                .cookie(HttpCookie("__Secure-JBH_TOKEN", TestTokens.token()))
        )

        assertThat(chainCalled).isTrue()
    }

    @Test
    fun `expired token gets 401 TOKEN_EXPIRED`() {
        val exchange = run(
            MockServerHttpRequest.get("/jbh-api/users/userinfo")
                .header("Authorization", "Bearer ${TestTokens.token(expiresInMillis = -60_000)}")
        )

        assertThat(chainCalled).isFalse()
        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(exchange.response.bodyAsString.block()).contains("AUTH_TOKEN_EXPIRED")
    }

    @Test
    fun `a path that only starts like the public prefix is still protected`() {
        run(MockServerHttpRequest.get("/jbh-api/authx/anything"))

        assertThat(chainCalled).isFalse()
    }
}
