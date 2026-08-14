package com.hyperscale.commerce.modules.order.api

import com.fasterxml.jackson.databind.ObjectMapper
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
private const val UNKNOWN_ORDER_ID = 999999L
private const val TIMEOUT_SECONDS = 15L
private const val POLL_MILLIS = 500L

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
  fun `gets an order by id from the read model`() {
    val created =
        objectMapper.readTree(
            post("/orders", """{"items":[{"sku":"$SKU_TWO","quantity":1}]}""").body())
    val orderId = created.get("id").asLong()

    val response = awaitOrder(orderId)
    assertThat(response.statusCode()).isEqualTo(200)
    val body = objectMapper.readTree(response.body())
    assertThat(body.get("id").asLong()).isEqualTo(orderId)
    assertThat(body.get("status").asText()).isEqualTo("PLACED")
    assertThat(body.get("items")[0].get("sku").asText()).isEqualTo(SKU_TWO)
  }

  @Test
  fun `lists orders with pagination from the read model`() {
    val first =
        objectMapper.readTree(
            post("/orders", """{"items":[{"sku":"$SKU_ONE","quantity":1}]}""").body())
    val second =
        objectMapper.readTree(
            post("/orders", """{"items":[{"sku":"$SKU_TWO","quantity":1}]}""").body())

    awaitOrder(first.get("id").asLong())
    awaitOrder(second.get("id").asLong())

    val response = get("/orders?page=0&size=1")
    assertThat(response.statusCode()).isEqualTo(200)
    val body = objectMapper.readTree(response.body())
    assertThat(body.get("total").asLong()).isEqualTo(2)
    assertThat(body.get("items")).hasSize(1)
  }

  @Test
  fun `returns 404 for unknown order`() {
    val response = get("/orders/$UNKNOWN_ORDER_ID")
    assertThat(response.statusCode()).isEqualTo(404)
  }

  @Test
  fun `rejects invalid order items`() {
    val response = post("/orders", """{"items":[{"sku":"$SKU_ONE","quantity":0}]}""")
    assertThat(response.statusCode()).isEqualTo(400)
  }

  private fun post(path: String, body: String): HttpResponse<String> {
    val request =
        HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
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
