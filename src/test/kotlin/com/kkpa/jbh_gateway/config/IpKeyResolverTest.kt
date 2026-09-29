package com.kkpa.jbh_gateway.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import java.net.InetSocketAddress

class IpKeyResolverTest {

    private val resolver = RateLimitConfig("10.231.0.0/24, not-a-cidr").ipKeyResolver()

    private fun resolve(remoteIp: String, forwardedFor: String? = null): String? {
        val request = MockServerHttpRequest.get("/jbh-api/users/userinfo")
            .remoteAddress(InetSocketAddress(remoteIp, 443))
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor)
        }
        return resolver.resolve(MockServerWebExchange.from(request)).block()
    }

    @Test
    fun `trusted proxy - uses the last X-Forwarded-For entry`() {
        assertThat(resolve("10.231.0.7", "6.6.6.6, 203.0.113.9")).isEqualTo("203.0.113.9")
    }

    @Test
    fun `loopback is trusted`() {
        assertThat(resolve("127.0.0.1", "203.0.113.9")).isEqualTo("203.0.113.9")
    }

    @Test
    fun `untrusted caller cannot fake the header`() {
        assertThat(resolve("198.51.100.4", "203.0.113.9")).isEqualTo("198.51.100.4")
    }

    @Test
    fun `trusted proxy without header - uses the proxy address`() {
        assertThat(resolve("10.231.0.7")).isEqualTo("10.231.0.7")
    }

    @Test
    fun `address outside the trusted subnet is not trusted`() {
        assertThat(resolve("10.231.1.7", "203.0.113.9")).isEqualTo("10.231.1.7")
    }
}
