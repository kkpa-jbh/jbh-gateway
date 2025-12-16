package com.kkpa.jbh_gateway.config

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

@Configuration
class RateLimitConfig {

    @Bean
    fun ipKeyResolver(): KeyResolver {
        return KeyResolver { exchange ->
            Mono.just(
                exchange.request.remoteAddress?.address?.hostAddress ?: "unknown"
            )
        }
    }

    @Bean
    fun inMemoryRateLimiter(): RateLimiter<InMemoryRateLimiter.Config> {
        return InMemoryRateLimiter()
    }
}

class InMemoryRateLimiter : RateLimiter<InMemoryRateLimiter.Config> {
    
    private val cache: Cache<String, WindowData> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .build()
    
    override fun isAllowed(routeId: String, id: String): Mono<Response> {
        val windowData = cache.get(id) { WindowData() }
        
        val currentTime = System.currentTimeMillis()
        val windowStart = currentTime / 1000 * 1000 // 1-second window
        
        if (windowData.windowStart != windowStart) {
            windowData.windowStart = windowStart
            windowData.requestCount.set(0)
        }
        
        val currentCount = windowData.requestCount.incrementAndGet()
        val allowed = currentCount <= 10 // 10 requests per second
        
        return Mono.just(Response(allowed, emptyMap()))
    }
    
    override fun getConfig(): Map<String, Config> = emptyMap()
    
    override fun getConfigClass(): Class<Config> = Config::class.java
    
    override fun newConfig(): Config = Config()
    
    data class Config(val dummy: String = "")
    
    private data class WindowData(
        var windowStart: Long = 0,
        val requestCount: AtomicInteger = AtomicInteger(0)
    )
}