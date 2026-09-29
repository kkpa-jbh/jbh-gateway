package com.kkpa.jbh_gateway

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

// Loads the whole context: routes, filters and rate limiter beans must wire.
// No Consul needed, and a test JWT secret (the real one has no default).
@SpringBootTest(
	properties = [
		"jwt.secret=${TestTokens.SECRET}",
		"spring.cloud.consul.enabled=false",
	]
)
class JbhGatewayApplicationTests {

	@Test
	fun contextLoads() {
	}

}
