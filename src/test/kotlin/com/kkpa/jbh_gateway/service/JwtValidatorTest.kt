package com.kkpa.jbh_gateway.service

import com.kkpa.jbh_gateway.TestTokens
import com.kkpa.jbh_gateway.model.AuthErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class JwtValidatorTest {

    private val validator = JwtValidator(TestTokens.properties())

    @Test
    fun `valid access token returns the user id`() {
        val result = validator.validateToken(TestTokens.token())

        assertThat(result).isInstanceOf(JwtValidator.ValidationResult.Valid::class.java)
        assertThat((result as JwtValidator.ValidationResult.Valid).userId).isEqualTo(TestTokens.USER_ID)
    }

    @Test
    fun `expired token is rejected`() {
        assertInvalid(TestTokens.token(expiresInMillis = -60_000), AuthErrorCode.TOKEN_EXPIRED)
    }

    @Test
    fun `token signed with another secret is rejected`() {
        assertInvalid(TestTokens.token(secret = TestTokens.OTHER_SECRET), AuthErrorCode.SIGNATURE_INVALID)
    }

    @Test
    fun `refresh token cannot be used as access token`() {
        assertInvalid(TestTokens.token(tokenType = "REFRESH"), AuthErrorCode.INVALID_TOKEN_TYPE)
    }

    @Test
    fun `token without subject is rejected`() {
        assertInvalid(TestTokens.token(subject = null), AuthErrorCode.INVALID_TOKEN)
    }

    @Test
    fun `garbage and empty tokens are rejected`() {
        assertInvalid("not-a-jwt", AuthErrorCode.INVALID_TOKEN)
        assertInvalid("", AuthErrorCode.INVALID_TOKEN)
    }

    @Test
    fun `missing secret stops the start`() {
        assertThatThrownBy { JwtValidator(TestTokens.properties(secret = "")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { JwtValidator(TestTokens.properties(secret = "\${JWT_SECRET}")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun assertInvalid(token: String, expected: AuthErrorCode) {
        val result = validator.validateToken(token)
        assertThat(result).isEqualTo(JwtValidator.ValidationResult.Invalid(expected))
    }
}
