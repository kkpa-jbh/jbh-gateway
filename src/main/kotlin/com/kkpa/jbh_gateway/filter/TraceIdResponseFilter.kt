package com.kkpa.jbh_gateway.filter

import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext
import org.springframework.core.Ordered
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Adds `X-Trace-Id` to every response, also 401, 429, 404 and actuator responses.
 *
 * This is a WebFilter, not a GlobalFilter: a GlobalFilter runs only for routed requests.
 * The id is read when the response is committed, because the server span does not exist yet
 * when `filter()` runs. The observation context is the same object on every thread, so the read
 * does not depend on a thread-local scope.
 */
@Component
class TraceIdResponseFilter : WebFilter, Ordered {

    companion object {
        const val TRACE_ID_HEADER = "X-Trace-Id"
    }

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        exchange.response.beforeCommit {
            addTraceIdHeader(exchange)
            Mono.empty()
        }
        return chain.filter(exchange)
    }

    // Must never fail a response: any problem means no header.
    private fun addTraceIdHeader(exchange: ServerWebExchange) {
        try {
            findTraceId(exchange)?.let { exchange.response.headers.set(TRACE_ID_HEADER, it) }
        } catch (ignored: RuntimeException) {
            // no header
        }
    }

    private fun findTraceId(exchange: ServerWebExchange): String? {
        val observationContext = ServerRequestObservationContext.findCurrent(exchange.attributes).orElse(null)
            ?: return null
        val tracingContext = observationContext.get<TracingContext>(TracingContext::class.java) ?: return null
        return tracingContext.span.context().traceId()?.takeIf { it.isNotEmpty() }
    }
}
