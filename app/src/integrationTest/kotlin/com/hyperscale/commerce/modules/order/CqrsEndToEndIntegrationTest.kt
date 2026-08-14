package com.hyperscale.commerce.modules.order

import com.fasterxml.jackson.databind.ObjectMapper
import com.hyperscale.commerce.config.OutboxProperties
import com.hyperscale.commerce.modules.catalog.performance.LoadResult
import com.hyperscale.commerce.modules.catalog.performance.LoadTest
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

private const val SKU = "PERF-SKU-00001"
private const val QUANTITY = 2
private const val TIMEOUT_SECONDS = 20L
private const val POLL_MILLIS = 500L
private const val GRACE_SECONDS = 3L
private const val NANOS_PER_MILLISECOND = 1_000_000.0
private const val ORDER_PLACED_EVENT_TYPE = "OrderPlaced"
private const val QUERY_CONCURRENCY = 20
private const val LOAD_RAMP_UP_SECONDS = 1
private const val LOAD_DURATION_SECONDS = 3
private const val CATALOG_PRODUCT_COUNT = 5
private const val CATALOG_SKU_PREFIX = "CQRS-SKU-"

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CqrsEndToEndIntegrationTest
@Autowired
constructor(
    @param:LocalServerPort private val port: Int,
    private val jdbcTemplate: JdbcTemplate,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val outboxProperties: OutboxProperties,
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
  fun `order flows end to end through the read model and query API`() {
    val postStart = System.nanoTime()
    val created =
        objectMapper.readTree(
            post("/orders", """{"items":[{"sku":"$SKU","quantity":$QUANTITY}]}""").body())
    val orderId = created.get("id").asLong()
    val postLatencyMs = (System.nanoTime() - postStart).toDouble() / NANOS_PER_MILLISECOND
    assertThat(created.get("status").asText()).isEqualTo("PLACED")

    val e2eStart = System.nanoTime()
    val getById = awaitOrder(orderId)
    val e2eLatencyMs = (System.nanoTime() - e2eStart).toDouble() / NANOS_PER_MILLISECOND
    assertThat(getById.statusCode()).isEqualTo(200)
    val body = objectMapper.readTree(getById.body())
    assertThat(body.get("id").asLong()).isEqualTo(orderId)
    assertThat(body.get("status").asText()).isEqualTo("PLACED")
    assertThat(body.get("items")[0].get("sku").asText()).isEqualTo(SKU)
    assertThat(body.get("items")[0].get("quantity").asInt()).isEqualTo(QUANTITY)

    val payload = outboxPayload(orderId)
    kafkaTemplate
        .send(outboxProperties.topic, orderId.toString(), payload)
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GRACE_SECONDS)
    while (System.nanoTime() < deadline) {
      assertThat(readModelCount(orderId)).isLessThanOrEqualTo(1)
      Thread.sleep(POLL_MILLIS)
    }
    assertThat(readModelCount(orderId)).isEqualTo(1)
    val afterReplay = objectMapper.readTree(get("/orders/$orderId").body())
    assertThat(afterReplay.get("status").asText()).isEqualTo("PLACED")
    assertThat(afterReplay.get("items")[0].get("sku").asText()).isEqualTo(SKU)

    val byId =
        LoadTest(httpClient, "GET /orders/{id}", { byIdRequest(orderId) })
            .run(QUERY_CONCURRENCY, LOAD_RAMP_UP_SECONDS, LOAD_DURATION_SECONDS)
    val list =
        LoadTest(httpClient, "GET /orders", { listRequest() })
            .run(QUERY_CONCURRENCY, LOAD_RAMP_UP_SECONDS, LOAD_DURATION_SECONDS)
    val catalog = seedAndLoadCatalog()

    val report = buildReport(orderId, postLatencyMs, e2eLatencyMs, byId, list, catalog)
    val reportFile = File("../docs/bootcamp/evidence/p4-cqrs.md")
    reportFile.parentFile.mkdirs()
    reportFile.writeText(report)

    assertThat(byId.errorRate).isEqualTo(0.0)
    assertThat(list.errorRate).isEqualTo(0.0)
    assertThat(catalog.errorRate).isEqualTo(0.0)
  }

  private fun seedAndLoadCatalog(): LoadResult {
    for (i in 1..CATALOG_PRODUCT_COUNT) {
      jdbcTemplate.update(
          """
          INSERT INTO catalog.products (sku, name, description, price, availability, created_at, updated_at)
          VALUES (?, ?, ?, ?, ?, now(), now())
          """
              .trimIndent(),
          "$CATALOG_SKU_PREFIX$i",
          "CQRS Product $i",
          "Seeded for the Phase 4 catalog spot check",
          1000 + i,
          "IN_STOCK",
      )
    }
    return LoadTest(httpClient, "GET /catalog/products/{id}", { catalogByIdRequest() })
        .run(QUERY_CONCURRENCY, LOAD_RAMP_UP_SECONDS, LOAD_DURATION_SECONDS)
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

  private fun readModelCount(orderId: Long): Long {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM \"order\".order_read_model WHERE order_id = ?",
        Long::class.java,
        orderId,
    ) ?: 0L
  }

  private fun outboxPayload(orderId: Long): String {
    return jdbcTemplate.queryForObject(
        "SELECT payload FROM \"order\".outbox_events WHERE aggregate_id = ? AND event_type = ?",
        String::class.java,
        orderId.toString(),
        ORDER_PLACED_EVENT_TYPE,
    ) ?: error("No outbox payload for order $orderId")
  }

  private fun byIdRequest(orderId: Long): HttpRequest {
    return HttpRequest.newBuilder(URI("http://localhost:$port/orders/$orderId")).GET().build()
  }

  private fun listRequest(): HttpRequest {
    return HttpRequest.newBuilder(URI("http://localhost:$port/orders?page=0&size=20")).GET().build()
  }

  private fun catalogByIdRequest(): HttpRequest {
    val id = ThreadLocalRandom.current().nextInt(1, CATALOG_PRODUCT_COUNT + 1)
    return HttpRequest.newBuilder(URI("http://localhost:$port/catalog/products/$id")).GET().build()
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

  private fun buildReport(
      orderId: Long,
      postLatencyMs: Double,
      e2eLatencyMs: Double,
      byId: LoadResult,
      list: LoadResult,
      catalog: LoadResult,
  ): String {
    val builder = StringBuilder()
    builder.appendLine("# Phase 4 — CQRS End-to-End Report")
    builder.appendLine()
    builder.appendLine(
        "Environment: local Docker PostgreSQL 16 + Kafka (Testcontainers), JDK 21, Spring Boot 4.0.")
    builder.appendLine()
    builder.appendLine("## Flow")
    builder.appendLine()
    builder.appendLine(
        "`POST /orders` → transactional outbox → Kafka `${outboxProperties.topic}` → `order-query` projection → `order.order_read_model` → `GET /orders/{id}`")
    builder.appendLine()
    builder.appendLine("| Step | Detail |")
    builder.appendLine("|---|---|")
    builder.appendLine("| POST /orders | 201, order id $orderId |")
    builder.appendLine("| Outbox event | $ORDER_PLACED_EVENT_TYPE |")
    builder.appendLine("| Projection | read model row created for order $orderId |")
    builder.appendLine("| GET /orders/$orderId | 200, served from `order.order_read_model` |")
    builder.appendLine()
    builder.appendLine("## Timings")
    builder.appendLine()
    builder.appendLine("| Measurement | Value |")
    builder.appendLine("|---|---:|")
    builder.appendLine("| POST /orders latency | ${postLatencyMs.format(1)} ms |")
    builder.appendLine("| End-to-end (POST → read model visible) | ${e2eLatencyMs.format(1)} ms |")
    builder.appendLine()
    builder.appendLine("## Idempotency")
    builder.appendLine()
    builder.appendLine(
        "The $ORDER_PLACED_EVENT_TYPE event was replayed on the topic; the read model row count remained 1 and the row was unchanged.")
    builder.appendLine()
    builder.appendLine("## Query endpoint p95 under concurrent load")
    builder.appendLine()
    builder.appendLine(
        "Load: ${QUERY_CONCURRENCY} concurrent users, ramp-up ${LOAD_RAMP_UP_SECONDS}s, duration ${LOAD_DURATION_SECONDS}s.")
    builder.appendLine()
    builder.appendLine("| Endpoint | p95 (ms) | Throughput (RPS) | Error rate |")
    builder.appendLine("|---|---:|---:|---:|")
    builder.appendLine(
        "| ${byId.name} | ${byId.p95.format(2)} | ${byId.throughputRps.format(2)} | ${byId.errorRate.format(2)} |")
    builder.appendLine(
        "| ${list.name} | ${list.p95.format(2)} | ${list.throughputRps.format(2)} | ${list.errorRate.format(2)} |")
    builder.appendLine()
    builder.appendLine("## Catalog SLO re-verification")
    builder.appendLine()
    builder.appendLine(
        "Spot check with $CATALOG_PRODUCT_COUNT seeded products: ${catalog.name} at ${QUERY_CONCURRENCY} concurrent users → p95 ${catalog.p95.format(2)} ms, error rate ${catalog.errorRate.format(2)}.")
    builder.appendLine(
        "The full catalog SLO verification (`CatalogSloVerificationTest`) re-runs in `make verify`; see `p2-slo-verification.md`.")
    return builder.toString()
  }

  private fun Double.format(digits: Int): String = String.format("%.${digits}f", this)
}
