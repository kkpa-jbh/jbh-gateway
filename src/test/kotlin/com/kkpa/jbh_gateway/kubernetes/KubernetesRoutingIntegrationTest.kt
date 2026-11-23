package com.kkpa.jbh_gateway.kubernetes

import com.kkpa.jbh_gateway.TestTokens
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer

/**
 * Guard test for routing without Consul (JBH-86).
 *
 * In Kubernetes, Consul is off. The routes keep `uri: lb://jbh-iam-service`, and Spring Cloud's simple
 * discovery maps that name to the Kubernetes Service URL (`http://jbh-iam:9999`), set by a ConfigMap in
 * jbh-deploy/k8s. Here a fake IAM on a random local port plays that Service.
 * If this test fails, the gateway cannot reach any service in the cluster.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "jwt.secret=${TestTokens.SECRET}",
        "spring.cloud.consul.enabled=false",
    ]
)
class KubernetesRoutingIntegrationTest {

    companion object {
        /** The name in the routes (`lb://jbh-iam-service`). The mapping below must use exactly this name. */
        private const val IAM_SERVICE_ID = "jbh-iam-service"

        // A fake IAM: answers "iam-ok" on the path the test calls. Reactor Netty is already on the
        // classpath (Spring WebFlux), so no new test dependency is needed.
        private val fakeIam: DisposableServer = HttpServer.create()
            .port(0)
            .route { routes ->
                routes.get("/jbh-api/users/find-user-id") { _, response -> response.sendString(Mono.just("iam-ok")) }
            }
            .bindNow()

        /** What the jbh-deploy ConfigMap does in the cluster: one instance per service name. */
        @JvmStatic
        @DynamicPropertySource
        fun simpleDiscovery(registry: DynamicPropertyRegistry) {
            registry.add("spring.cloud.discovery.client.simple.instances.$IAM_SERVICE_ID[0].uri") {
                "http://localhost:${fakeIam.port()}"
            }
        }

        @JvmStatic
        @AfterAll
        fun stopFakeIam() {
            fakeIam.disposeNow()
        }
    }

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `routes a user call to IAM through simple discovery`() {
        webTestClient.get()
            .uri("/jbh-api/users/find-user-id")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${TestTokens.token()}")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).isEqualTo("iam-ok")
    }
}
