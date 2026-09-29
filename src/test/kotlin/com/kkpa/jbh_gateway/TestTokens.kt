package com.kkpa.jbh_gateway

import com.kkpa.jbh_gateway.config.JwtProperties
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import java.util.Date
import javax.crypto.SecretKey

/**
 * Builds tokens the same way jbh-iam does (JwtTokenProvider): Base64 secret, HS256,
 * subject = user id, claim token_type = ACCESS. If jbh-iam changes that, change it here too.
 */
object TestTokens {
    // Test value only. Base64 of 44 bytes.
    const val SECRET = "amJoLXRlc3Qtc2VjcmV0LW9ubHktZm9yLXVuaXQtdGVzdHMtMzIrYnl0ZXM="
    const val OTHER_SECRET = "b3RoZXItc2VjcmV0LW9ubHktZm9yLXVuaXQtdGVzdHMtMzIrYnl0ZXM="
    const val USER_ID = "3f2b8c1e-4d5a-4b6c-9e7f-0a1b2c3d4e5f"

    fun properties(secret: String = SECRET) =
        JwtProperties(secret = secret, tokenTypeClaim = "token_type", accessTokenType = "ACCESS")

    fun key(secret: String = SECRET): SecretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))

    fun token(
        subject: String? = USER_ID,
        tokenType: String = "ACCESS",
        expiresInMillis: Long = 60_000,
        secret: String = SECRET
    ): String {
        val now = System.currentTimeMillis()
        val builder = Jwts.builder()
            .issuedAt(Date(now))
            .expiration(Date(now + expiresInMillis))
            .claim("token_type", tokenType)
        if (subject != null) {
            builder.subject(subject)
        }
        return builder.signWith(key(secret), Jwts.SIG.HS256).compact()
    }
}
