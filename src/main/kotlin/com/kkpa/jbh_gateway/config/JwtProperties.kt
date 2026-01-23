package com.kkpa.jbh_gateway.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "jwt")
data class JwtProperties(
    var secret: String = "",
    var tokenTypeClaim: String = "tokenType",
    var accessTokenType: String = "ACCESS_TOKEN"
)
