package com.hyperscale.commerce.modules.order.api

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

private const val ORDER_PLACED_EVENT_TYPE = "OrderPlaced"
private const val SKU_ONE = "PERF-SKU-00001"
private const val SKU_TWO = "PERF-SKU-00002"

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderControllerIntegrationTest
@Autowired
constructor(
    @param:LocalServerPort private val port: Int,
    private val jdbcTemplate: JdbcTemplate,
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

    @JvmStatic
    @DynamicPropertySource
    fun kafkaProperties(registry: DynamicPropertyRegistry) {
      registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
    }
  }

  @Test
  fun `creates an order and writes one outbox event`() {
    val response = post("/orders", """{"items":[{"sku":"$SKU_ONE","quantity":2}]}""")

    assertThat(response.statusCode()).isEqualTo(201)
    val body = objectMapper.readTree(response.body())
    val orderId = body.get("id").asLong()
    assertThat(body.get("status").asText()).isEqualTo("PLACED")
    assertThat(body.get("items")).hasSize(1)
    assertThat(body.get("items")[0].get("sku").asText()).isEqualTo(SKU_ONE)
    assertThat(body.get("items")[0].get("quantity").asInt()).isEqualTo(2)

    val outboxCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM \"order\".outbox_events WHERE event_type = ? AND aggregate_id = ?",
            Long::class.java,
            ORDER_PLACED_EVENT_TYPE,
            orderId.toString(),
        )
    assertThat(outboxCount).isEqualTo(1)
  }

  @Test
  fun `rejects invalid order items`() {
    val response = post("/orders", """{"items":[{"sku":"$SKU_ONE","quantity":0}]}""")
    assertThat(response.statusCode()).isEqualTo(400)
  }

  @Test
  fun `idempotency key deduplicates client retries and returns cached 201`() {
    val idempotencyKey = UUID.randomUUID().toString()
    val payload = """{"items":[{"sku":"$SKU_ONE","quantity":5}]}"""

    val response1 = postWithKey("/orders", payload, idempotencyKey)
    assertThat(response1.statusCode()).isEqualTo(201)
    val body1 = objectMapper.readTree(response1.body())
    val orderId1 = body1.get("id").asLong()

    val response2 = postWithKey("/orders", payload, idempotencyKey)
    assertThat(response2.statusCode()).isEqualTo(201)
    val body2 = objectMapper.readTree(response2.body())
    val orderId2 = body2.get("id").asLong()

    assertThat(orderId1).isEqualTo(orderId2)

    val keyRecordCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM \"order\".idempotency_keys WHERE key = ? AND status = 'COMPLETED'",
            Long::class.java,
            idempotencyKey,
        )
    assertThat(keyRecordCount).isEqualTo(1)
  }

  @Test
  fun `idempotency key with differing payload returns 422 unprocessable entity`() {
    val idempotencyKey = UUID.randomUUID().toString()
    val payload1 = """{"items":[{"sku":"$SKU_ONE","quantity":1}]}"""
    val payload2 = """{"items":[{"sku":"$SKU_TWO","quantity":9}]}"""

    val response1 = postWithKey("/orders", payload1, idempotencyKey)
    assertThat(response1.statusCode()).isEqualTo(201)

    val response2 = postWithKey("/orders", payload2, idempotencyKey)
    assertThat(response2.statusCode()).isEqualTo(422)
  }

  private fun post(path: String, body: String): HttpResponse<String> {
    val request =
        HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }

  private fun postWithKey(path: String, body: String, key: String): HttpResponse<String> {
    val request =
        HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }
}
