package com.kkpa.jbh_gateway.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InMemoryRateLimiterTest {

    private var now = 1_000_000L
    private val limiter = InMemoryRateLimiter(maxRequests = 3, windowMillis = 1_000, clock = { now })

    private fun allowed(key: String) = limiter.isAllowed("route", key).block()!!.isAllowed

    @Test
    fun `allows up to the limit in one window, then blocks`() {
        repeat(3) { assertThat(allowed("1.1.1.1")).isTrue() }
        assertThat(allowed("1.1.1.1")).isFalse()
    }

    @Test
    fun `each key has its own counter`() {
        repeat(3) { allowed("1.1.1.1") }

        assertThat(allowed("1.1.1.1")).isFalse()
        assertThat(allowed("2.2.2.2")).isTrue()
    }

    @Test
    fun `a new window resets the counter`() {
        repeat(4) { allowed("1.1.1.1") }

        now += 1_000

        assertThat(allowed("1.1.1.1")).isTrue()
    }
}
