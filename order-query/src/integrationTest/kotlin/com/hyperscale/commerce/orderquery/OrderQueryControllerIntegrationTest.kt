package com.hyperscale.commerce.orderquery

import com.fasterxml.jackson.databind.ObjectMapper
import com.hyperscale.commerce.orderquery.testsupport.IsolatedRedisContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
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

private const val SKU_ONE = "PERF-SKU-00001"
private const val SKU_TWO = "PERF-SKU-00002"
private const val UNKNOWN_ORDER_ID = 999999L
private const val TIMEOUT_SECONDS = 15L
private const val POLL_MILLIS = 500L

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderQueryControllerIntegrationTest
@Autowired
constructor(
    @param:LocalServerPort private val port: Int,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    @param:Value("\${app.outbox.topic}") private val topic: String,
) {
  private val httpClient = HttpClient.newHttpClient()
  private val objectMapper = ObjectMapper()

  companion object {
    @Container
    @JvmStatic
    val kafka: KafkaContainer = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))

    @Container
    @JvmStatic
    @ServiceConnection
    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

    @Container
    @JvmStatic
    val redis =
        IsolatedRedisContainer(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379)

    @JvmStatic
    @DynamicPropertySource
    fun kafkaProperties(registry: DynamicPropertyRegistry) {
      registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
      registry.add("spring.data.redis.host") { redis.host }
      registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
    }
  }

  @Test
  fun `gets an order by id from the read model`() {
    val orderId = 1001L
    publishOrderPlaced(orderId, SKU_TWO, 1)

    val response = awaitOrder(orderId)
    assertThat(response.statusCode()).isEqualTo(200)
    val body = objectMapper.readTree(response.body())
    assertThat(body.get("id").asLong()).isEqualTo(orderId)
    assertThat(body.get("status").asText()).isEqualTo("PLACED")
    assertThat(body.get("items")[0].get("sku").asText()).isEqualTo(SKU_TWO)
  }

  @Test
  fun `lists orders with pagination from the read model`() {
    val firstId = 2001L
    val secondId = 2002L
    publishOrderPlaced(firstId, SKU_ONE, 1)
    publishOrderPlaced(secondId, SKU_TWO, 1)

    awaitOrder(firstId)
    awaitOrder(secondId)

    val response = get("/orders?page=0&size=1")
    assertThat(response.statusCode()).isEqualTo(200)
    val body = objectMapper.readTree(response.body())
    assertThat(body.get("total").asLong()).isGreaterThanOrEqualTo(2)
    assertThat(body.get("items")).hasSize(1)
  }

  @Test
  fun `returns 404 for unknown order`() {
    val response = get("/orders/$UNKNOWN_ORDER_ID")
    assertThat(response.statusCode()).isEqualTo(404)
  }

  private fun publishOrderPlaced(orderId: Long, sku: String, quantity: Int) {
    val payload =
        """{"version":1,"eventId":"event-$orderId","orderId":$orderId,"status":"PLACED","createdAt":"2026-08-14T00:00:00Z","items":[{"sku":"$sku","quantity":$quantity}]}"""
    kafkaTemplate.send(topic, orderId.toString(), payload).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
