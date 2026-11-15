package com.kkpa.jbh_gateway.observation

import io.micrometer.common.KeyValue
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationFilter
import org.springframework.cloud.gateway.filter.headers.observation.GatewayContext
import org.springframework.stereotype.Component

/**
 * Removes the query string from the gateway client span attribute `http.uri`.
 * The default convention writes the full URI, so `?email=x@y.z` would reach the trace backend.
 * Spring Boot adds every `ObservationFilter` bean to the observation registry.
 */
@Component
class GatewayClientUriObservationFilter : ObservationFilter {

    companion object {
        private const val HTTP_URI_KEY = "http.uri"
    }

    override fun map(context: Observation.Context): Observation.Context {
        if (context !is GatewayContext) return context
        val uri = context.request.uri
        val withoutQuery = "${uri.scheme}://${uri.rawAuthority}${uri.rawPath}"
        context.addHighCardinalityKeyValue(KeyValue.of(HTTP_URI_KEY, withoutQuery))
        return context
    }
}
