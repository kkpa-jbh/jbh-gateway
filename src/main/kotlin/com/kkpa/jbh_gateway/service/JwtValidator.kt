package com.kkpa.jbh_gateway.service

import com.kkpa.jbh_gateway.config.JwtProperties
import com.kkpa.jbh_gateway.model.AuthErrorCode
import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.MalformedJwtException
import io.jsonwebtoken.UnsupportedJwtException
import io.jsonwebtoken.security.Keys
import io.jsonwebtoken.security.SignatureException
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets

@Service
class JwtValidator(private val jwtProperties: JwtProperties) {

    private val secretKey by lazy {
        Keys.hmacShaKeyFor(jwtProperties.secret.toByteArray(StandardCharsets.UTF_8))
    }

    /*
    All JWT HMAC secrets are Base64-encoded and must be Base64-decoded before use.
    val claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .payload
     */

    fun validateToken(token: String): ValidationResult {
        try {
            val claims = Jwts.parser()
                .setSigningKey(jwtProperties.secret)
                .build()
                .parseClaimsJws(token)
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
        } catch (ex: MalformedJwtException) {
            return ValidationResult.Invalid(AuthErrorCode.INVALID_TOKEN)
        } catch (ex: UnsupportedJwtException) {
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
