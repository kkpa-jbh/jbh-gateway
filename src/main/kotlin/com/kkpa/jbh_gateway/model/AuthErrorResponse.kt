package com.kkpa.jbh_gateway.model

import com.fasterxml.jackson.annotation.JsonProperty

data class AuthErrorResponse(
    @JsonProperty("error_code")
    val errorCode: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)
