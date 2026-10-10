package com.kkpa.jbh_gateway.service

import com.kkpa.jbh_gateway.config.JwtProperties
import com.kkpa.jbh_gateway.model.AuthErrorCode
import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import io.jsonwebtoken.security.SignatureException
import org.springframework.stereotype.Service
import javax.crypto.SecretKey

@Service
class JwtValidator(private val jwtProperties: JwtProperties) {

    // The secret is Base64. jbh-iam decodes it the same way to sign the tokens (HS256).
    // Built at start: a missing or bad JBH_JWT_SECRET stops the gateway instead of rejecting every request.
    // (@ConfigurationProperties keeps an unresolved "${JBH_JWT_SECRET}" as text, so this check is needed.)
    private val secretKey: SecretKey = run {
        val secret = jwtProperties.secret
        require(secret.isNotBlank() && !secret.startsWith("\${")) { "Set env JBH_JWT_SECRET (Base64, at least 32 bytes)" }
        Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))
    }

    fun validateToken(token: String): ValidationResult {
        try {
            val claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .payload

            val tokenType = claims[jwtProperties.tokenTypeClaim] as? String
            if (tokenType != jwtProperties.accessTokenType) {
                return ValidationResult.Invalid(AuthErrorCode.INVALID_TOKEN_TYPE)
            }

            val userId = claims.subject
            if (userId.isNullOrBlank()) {
                return ValidationResult.Invalid(AuthErrorCode.INVALID_TOKEN)
            }

            return ValidationResult.Valid(userId, claims)

        } catch (ex: ExpiredJwtException) {
            return ValidationResult.Invalid(AuthErrorCode.TOKEN_EXPIRED)
        } catch (ex: SignatureException) {
            return ValidationResult.Invalid(AuthErrorCode.SIGNATURE_INVALID)
        } catch (ex: JwtException) {
            return ValidationResult.Invalid(AuthErrorCode.INVALID_TOKEN)
        } catch (ex: IllegalArgumentException) {
            return ValidationResult.Invalid(AuthErrorCode.INVALID_TOKEN)
        }
    }

    sealed class ValidationResult {
        data class Valid(val userId: String, val claims: Claims) : ValidationResult()
        data class Invalid(val errorCode: AuthErrorCode) : ValidationResult()
    }
}
