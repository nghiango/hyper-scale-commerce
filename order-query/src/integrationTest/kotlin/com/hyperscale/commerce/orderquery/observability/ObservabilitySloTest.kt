package com.hyperscale.commerce.orderquery.observability

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

private const val SKU = "PERF-SKU-00001"
private const val TIMEOUT_SECONDS = 15L
private const val POLL_MILLIS = 500L

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilitySloTest
@Autowired
constructor(
    @param:LocalServerPort private val port: Int,
    private val kafkaTemplate: KafkaTemplate<String, String>,
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
  fun `GET order p95 is observable in prometheus`() {
    val orderId = 3001L
    publishOrderPlaced(orderId)

    awaitOrder(orderId)

    val listResponse = get("/orders?page=0&size=10")
    assertThat(listResponse.statusCode()).isEqualTo(200)

    val prometheus = get("/actuator/prometheus").body()
    assertThat(prometheus).contains("slo_get_order_by_id_p95")
    assertThat(prometheus).contains("slo_get_orders_p95")
    assertThat(prometheus).contains("""http_server_requests_seconds""")
    assertThat(prometheus).contains("""method=""")
    assertThat(prometheus).contains("""GET""")
    assertThat(prometheus).contains("""0.95""")
  }

  private fun publishOrderPlaced(orderId: Long) {
    val payload =
        """{"version":1,"eventId":"event-$orderId","orderId":$orderId,"status":"PLACED","createdAt":"2026-08-14T00:00:00Z","items":[{"sku":"$SKU","quantity":1}]}"""
    kafkaTemplate
        .send("order-placed", orderId.toString(), payload)
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
  }

  private fun get(path: String): HttpResponse<String> {
    val request = HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }

  private fun awaitOrder(orderId: Long): HttpResponse<String> {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      val response = get("/orders/$orderId")
      if (response.statusCode() == 200) {
        return response
      }
      Thread.sleep(POLL_MILLIS)
    }
    return get("/orders/$orderId")
  }
}
