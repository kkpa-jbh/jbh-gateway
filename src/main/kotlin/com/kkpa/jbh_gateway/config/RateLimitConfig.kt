package com.kkpa.jbh_gateway.config

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response
import org.springframework.http.server.reactive.ServerHttpRequest
import reactor.core.publisher.Mono
import java.net.InetAddress
import java.time.Duration

/**
 * Rate limit key = the real client IP.
 *
 * In production, Magus's Caddy calls the gateway from the Docker network `edge`, not from loopback.
 * So every request arrives from the Caddy container IP. The real client IP is in `X-Forwarded-For`.
 * Caddy replaces any `X-Forwarded-For` the client sends, so the last entry is the real client.
 *
 * The header is trusted only when the direct caller is loopback or inside `jbh.rate-limit.trusted-proxies`
 * (env `JBH_TRUSTED_PROXIES`, comma-separated CIDRs, e.g. the `edge` subnet). From anyone else the header
 * is ignored, so a client cannot fake it to get around the limit.
 */
@Configuration
class RateLimitConfig(
    @Value("\${jbh.rate-limit.trusted-proxies:}") trustedProxies: String
) {

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(RateLimitConfig::class.java)
        private const val FORWARDED_FOR_HEADER = "X-Forwarded-For"
    }

    private val trustedNetworks: List<IpNetwork> = trustedProxies.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { cidr ->
            IpNetwork.parse(cidr) ?: run {
                LOG.warn("Ignoring invalid trusted proxy CIDR: {}", cidr)
                null
            }
        }

    @Bean
    fun ipKeyResolver(): KeyResolver {
        return KeyResolver { exchange -> Mono.just(resolveClientIp(exchange.request)) }
    }

    private fun resolveClientIp(request: ServerHttpRequest): String {
        val remoteAddress = request.remoteAddress?.address ?: return "unknown"
        if (!isTrustedProxy(remoteAddress)) {
            return remoteAddress.hostAddress
        }
        val lastForwardedFor = request.headers.getFirst(FORWARDED_FOR_HEADER)
            ?.split(",")
            ?.map { it.trim() }
            ?.lastOrNull { it.isNotEmpty() }
        return lastForwardedFor ?: remoteAddress.hostAddress
    }

    private fun isTrustedProxy(address: InetAddress): Boolean =
        address.isLoopbackAddress || trustedNetworks.any { it.contains(address) }

    /** Default for all routes: 10 requests per second per IP. @Primary: the gateway autowires one default limiter. */
    @Bean
    @Primary
    fun inMemoryRateLimiter(): RateLimiter<InMemoryRateLimiter.Config> {
        return InMemoryRateLimiter(maxRequests = 10, windowMillis = 1_000)
    }

    /** Sign-in, sign-up and Google sign-in: 10 requests per minute per IP, against password guessing. */
    @Bean
    fun authRateLimiter(): RateLimiter<InMemoryRateLimiter.Config> {
        return InMemoryRateLimiter(maxRequests = 10, windowMillis = 60_000)
    }
}

/** A CIDR range such as `10.231.0.0/24`. IPv4 and IPv6 never match each other. */
private class IpNetwork(private val network: ByteArray, private val prefixLength: Int) {

    fun contains(address: InetAddress): Boolean {
        val candidate = address.address
        if (candidate.size != network.size) {
            return false
        }
        val fullBytes = prefixLength / 8
        for (i in 0 until fullBytes) {
            if (candidate[i] != network[i]) {
                return false
            }
        }
        val remainingBits = prefixLength % 8
        if (remainingBits == 0) {
            return true
        }
        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        return (candidate[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask)
    }

    companion object {
        /** Returns null for anything that is not a literal IP with a valid prefix (no DNS lookup). */
        fun parse(cidr: String): IpNetwork? {
            val parts = cidr.split("/")
            if (parts.size != 2) {
                return null
            }
            val ip = parts[0]
            val isIpLiteral = ip.contains(':') || ip.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
            if (!isIpLiteral) {
                return null
            }
            val bytes = runCatching { InetAddress.getByName(ip).address }.getOrNull() ?: return null
            val prefix = parts[1].toIntOrNull() ?: return null
            if (prefix < 0 || prefix > bytes.size * 8) {
                return null
            }
            return IpNetwork(bytes, prefix)
        }
    }
}

/** Fixed-window counter per key (client IP): at most [maxRequests] in each window of [windowMillis]. */
class InMemoryRateLimiter(
    private val maxRequests: Int,
    private val windowMillis: Long
) : RateLimiter<InMemoryRateLimiter.Config> {

    // Entries live a bit longer than one window, so an idle IP starts fresh.
    private val cache: Cache<String, WindowData> = Caffeine.newBuilder()
        .expireAfterAccess(Duration.ofMillis(windowMillis * 2))
        .build()

    override fun isAllowed(routeId: String, id: String): Mono<Response> {
        val windowData = cache.get(id) { WindowData() }
        val windowStart = System.currentTimeMillis() / windowMillis * windowMillis

        // Reset and count together, so two parallel requests cannot both reset the window.
        val currentCount = synchronized(windowData) {
            if (windowData.windowStart != windowStart) {
                windowData.windowStart = windowStart
                windowData.requestCount = 0
            }
            ++windowData.requestCount
        }

        return Mono.just(Response(currentCount <= maxRequests, emptyMap()))
    }

    override fun getConfig(): Map<String, Config> = emptyMap()

    override fun getConfigClass(): Class<Config> = Config::class.java

    override fun newConfig(): Config = Config()

    data class Config(val dummy: String = "")

    private class WindowData(
        var windowStart: Long = 0,
        var requestCount: Int = 0
    )
}
