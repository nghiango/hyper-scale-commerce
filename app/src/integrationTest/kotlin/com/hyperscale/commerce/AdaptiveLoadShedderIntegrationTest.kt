package com.hyperscale.commerce

import com.hyperscale.commerce.config.loadshedding.AdaptiveLoadShedderFilter
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
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

private const val SKU_ONE = "PERF-SKU-00001"

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdaptiveLoadShedderIntegrationTest
@Autowired
constructor(
    @param:LocalServerPort private val port: Int,
    private val loadShedder: AdaptiveLoadShedderFilter,
) {
  private val httpClient = HttpClient.newHttpClient()

  companion object {
    @Container
    @JvmStatic
    val kafka: KafkaContainer = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))

    @Container
    @JvmStatic
    @ServiceConnection
    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

    @JvmStatic
    @DynamicPropertySource
    fun kafkaProperties(registry: DynamicPropertyRegistry) {
      registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
    }
  }

  @Test
  fun `sheds degradable catalog traffic during overload while preserving checkout and health`() {
    // 1. Normal state: catalog returns 200
    val normalCatalog = get("/catalog/products?page=0&size=10")
    assertThat(normalCatalog.statusCode()).isEqualTo(200)

    val normalOrder = post("/orders", """{"items":[{"sku":"$SKU_ONE","quantity":1}]}""")
    assertThat(normalOrder.statusCode()).isEqualTo(201)

    // 2. Overloaded state
    loadShedder.setForcedOverload(true)
    try {
      val shedCatalog = get("/catalog/products?page=0&size=10")
      assertThat(shedCatalog.statusCode()).isEqualTo(429)
      assertThat(shedCatalog.headers().firstValue("Retry-After")).contains("5")
      assertThat(shedCatalog.body()).contains("temporarily shed")

      // Critical checkout path remains 100% available
      val checkoutDuringOverload =
          post("/orders", """{"items":[{"sku":"$SKU_ONE","quantity":1}]}""")
      assertThat(checkoutDuringOverload.statusCode()).isEqualTo(201)

      // Actuator health remains available
      val health = get("/actuator/health/liveness")
      assertThat(health.statusCode()).isEqualTo(200)
    } finally {
      loadShedder.setForcedOverload(false)
    }

    // 3. Recovered state
    val recoveredCatalog = get("/catalog/products?page=0&size=10")
    assertThat(recoveredCatalog.statusCode()).isEqualTo(200)
  }

  private fun get(path: String): HttpResponse<String> {
    val request = HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }

  private fun post(path: String, body: String): HttpResponse<String> {
    val request =
        HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }
}
