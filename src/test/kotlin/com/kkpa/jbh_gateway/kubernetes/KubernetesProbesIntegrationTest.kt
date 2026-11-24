package com.kkpa.jbh_gateway.kubernetes

import com.kkpa.jbh_gateway.TestTokens
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Guard test for the Kubernetes probes (JBH-86).
 *
 * The kubelet calls the liveness and readiness probes with no token. Spring Boot turns the probe groups
 * on by itself when it runs on Kubernetes; `spring.main.cloud-platform=kubernetes` makes the test see the
 * same thing. `AuthorizationGlobalFilter` covers only routed requests, so the actuator paths stay open.
 * If that changes, a gateway pod would never become Ready.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "jwt.secret=${TestTokens.SECRET}",
        "spring.cloud.consul.enabled=false",
        "spring.main.cloud-platform=kubernetes",
    ]
)
class KubernetesProbesIntegrationTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `liveness probe answers UP without a token`() {
        webTestClient.get().uri("/actuator/health/liveness").exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.status").isEqualTo("UP")
    }

    @Test
    fun `readiness probe answers UP without a token`() {
        webTestClient.get().uri("/actuator/health/readiness").exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.status").isEqualTo("UP")
    }
}
