package com.hyperscale.commerce.observability

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilitySloTest @Autowired constructor(@param:LocalServerPort private val port: Int) {
  private val httpClient = HttpClient.newHttpClient()

  companion object {
    @Container
    @JvmStatic
    @ServiceConnection
    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

    @JvmStatic
    @DynamicPropertySource
    fun kafkaProperties(registry: DynamicPropertyRegistry) {
      registry.add("spring.kafka.listener.auto-startup") { "false" }
    }
  }

  @Test
  fun `POST orders p95 and success rate are observable in prometheus`() {
    val success = post("""{"items":[{"sku":"PERF-SKU-00001","quantity":1}]}""")
    assertThat(success.statusCode()).isEqualTo(201)

    val failure = post("""{"items":[]}""")
    assertThat(failure.statusCode()).isBetween(400, 499)

    val prometheus = get("/actuator/prometheus").body()
    assertThat(prometheus).contains("slo_post_orders_success_rate")
    assertThat(prometheus).contains("slo_get_order_by_id_p95")
    assertThat(prometheus).contains("slo_get_orders_p95")
    assertThat(prometheus).contains("""http_server_requests_seconds""")
    assertThat(prometheus).contains("""method="""")
    assertThat(prometheus).contains("""POST""")
    assertThat(prometheus).contains("""0.95""")
  }

  private fun post(body: String): HttpResponse<String> {
    val request =
        HttpRequest.newBuilder(URI("http://localhost:$port/orders"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }

  private fun get(path: String): HttpResponse<String> {
    val request = HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }
}
