package com.kkpa.jbh_gateway.model

enum class AuthErrorCode(val code: String, val message: String) {
    TOKEN_EXPIRED("AUTH_TOKEN_EXPIRED", "Access token has expired"),
    INVALID_TOKEN("AUTH_INVALID_TOKEN", "Invalid or malformed token"),
    INVALID_TOKEN_TYPE("AUTH_INVALID_TT", "Invalid TT"),
    MISSING_TOKEN("AUTH_MISSING_TOKEN", "Authorization token is required"),
    SIGNATURE_INVALID("AUTH_SI", "SI  failed") // Token signature
}
