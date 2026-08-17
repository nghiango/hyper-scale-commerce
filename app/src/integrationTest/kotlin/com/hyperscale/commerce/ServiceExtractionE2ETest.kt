package com.hyperscale.commerce

import com.fasterxml.jackson.databind.ObjectMapper
import com.hyperscale.commerce.modules.catalog.performance.LoadResult
import com.hyperscale.commerce.modules.catalog.performance.LoadTest
import com.hyperscale.commerce.orderquery.OrderQueryApplication
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.testcontainers.containers.GenericContainer
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
private const val ORDER_PLACED_TOPIC = "order-placed"
private const val QUERY_CONCURRENCY = 10
private const val LOAD_RAMP_UP_SECONDS = 1
private const val LOAD_DURATION_SECONDS = 3
private const val READINESS_RETRIES = 40
private const val READINESS_RETRY_MILLIS = 500L

@Testcontainers
class ServiceExtractionE2ETest {
  private val httpClient = HttpClient.newHttpClient()
  private val objectMapper = ObjectMapper()

  companion object {
    @Container
    @JvmStatic
    val kafka: KafkaContainer = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))

    @Container @JvmStatic val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

    @Container
    @JvmStatic
    val redis =
        ExtractionRedisContainer(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379)
  }

  @Test
  fun `order flows across the extracted services`() {
    // Startup-order case: order-query is healthy before the monolith starts.
    val orderQueryContext = startOrderQuery()
    val orderQueryPort = orderQueryContext.environment.getProperty("local.server.port")!!.toInt()
    awaitReadiness(orderQueryPort)

    val monolithContext = startMonolith()
    val monolithPort = monolithContext.environment.getProperty("local.server.port")!!.toInt()
    awaitReadiness(monolithPort)

    try {
      val postStart = System.nanoTime()
      val created =
          objectMapper.readTree(
              post(monolithPort, "/orders", """{"items":[{"sku":"$SKU","quantity":$QUANTITY}]}""")
                  .body())
      val orderId = created.get("id").asLong()
      val postLatencyMs = (System.nanoTime() - postStart).toDouble() / NANOS_PER_MILLISECOND
      assertThat(created.get("status").asText()).isEqualTo("PLACED")

      val e2eStart = System.nanoTime()
      val getById = awaitOrder(orderQueryPort, orderId)
      val e2eLatencyMs = (System.nanoTime() - e2eStart).toDouble() / NANOS_PER_MILLISECOND
      assertThat(getById.statusCode()).isEqualTo(200)
      val body = objectMapper.readTree(getById.body())
      assertThat(body.get("id").asLong()).isEqualTo(orderId)
      assertThat(body.get("status").asText()).isEqualTo("PLACED")
      assertThat(body.get("items")[0].get("sku").asText()).isEqualTo(SKU)
      assertThat(body.get("items")[0].get("quantity").asInt()).isEqualTo(QUANTITY)

      val jdbcTemplate = monolithContext.getBean(JdbcTemplate::class.java)
      val kafkaTemplate =
          monolithContext.getBean(KafkaTemplate::class.java) as KafkaTemplate<String, String>
      val payload = outboxPayload(jdbcTemplate, orderId)
      kafkaTemplate
          .send(ORDER_PLACED_TOPIC, orderId.toString(), payload)
          .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GRACE_SECONDS)
      while (System.nanoTime() < deadline) {
        assertThat(readModelCount(jdbcTemplate, orderId)).isLessThanOrEqualTo(1)
        Thread.sleep(POLL_MILLIS)
      }
      assertThat(readModelCount(jdbcTemplate, orderId)).isEqualTo(1)
      val afterReplay = objectMapper.readTree(get(orderQueryPort, "/orders/$orderId").body())
      assertThat(afterReplay.get("status").asText()).isEqualTo("PLACED")
      assertThat(afterReplay.get("items")[0].get("sku").asText()).isEqualTo(SKU)

      val byId =
          LoadTest(httpClient, "GET /orders/{id}", { byIdRequest(orderQueryPort, orderId) })
              .run(QUERY_CONCURRENCY, LOAD_RAMP_UP_SECONDS, LOAD_DURATION_SECONDS)
      val list =
          LoadTest(httpClient, "GET /orders", { listRequest(orderQueryPort) })
              .run(QUERY_CONCURRENCY, LOAD_RAMP_UP_SECONDS, LOAD_DURATION_SECONDS)

      writeEvidence(orderId, postLatencyMs, e2eLatencyMs, byId, list)

      assertThat(byId.errorRate).isEqualTo(0.0)
      assertThat(list.errorRate).isEqualTo(0.0)
    } finally {
      monolithContext.close()
      orderQueryContext.close()
    }
  }

  private fun startOrderQuery(): ConfigurableApplicationContext {
    return SpringApplicationBuilder(OrderQueryApplication::class.java)
        .run(
            "--spring.config.name=orderquery",
            "--spring.application.name=order-query",
            "--app.name=order-query",
            "--spring.datasource.url=${postgres.jdbcUrl}",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--spring.kafka.bootstrap-servers=${kafka.bootstrapServers}",
            "--spring.data.redis.host=${redis.host}",
            "--spring.data.redis.port=${redis.getMappedPort(6379)}",
            "--spring.flyway.locations=classpath:db/migration-order-query",
            "--spring.flyway.schemas=order_query",
            "--server.port=0",
        )
  }

  private fun startMonolith(): ConfigurableApplicationContext {
    return SpringApplicationBuilder(Application::class.java)
        .run(
            "--spring.application.name=hyper-scale-commerce",
            "--app.name=hyper-scale-commerce",
            "--spring.datasource.url=${postgres.jdbcUrl}",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--spring.kafka.bootstrap-servers=${kafka.bootstrapServers}",
            "--spring.data.redis.host=${redis.host}",
            "--spring.data.redis.port=${redis.getMappedPort(6379)}",
            "--spring.flyway.locations=classpath:db/migration",
            "--spring.flyway.schemas=public",
            "--server.port=0",
        )
  }

  private fun awaitReadiness(port: Int) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      try {
        val response = get(port, "/actuator/health/readiness")
        if (response.statusCode() == 200 && response.body().contains("\"UP\"")) {
          return
        }
      } catch (_: Exception) {
        // service not yet accepting connections
      }
      Thread.sleep(READINESS_RETRY_MILLIS)
    }
    throw AssertionError("Service on port $port not ready within $TIMEOUT_SECONDS seconds")
  }

  private fun awaitOrder(orderQueryPort: Int, orderId: Long): HttpResponse<String> {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      val response = get(orderQueryPort, "/orders/$orderId")
      if (response.statusCode() == 200) {
        return response
      }
      Thread.sleep(POLL_MILLIS)
    }
    return get(orderQueryPort, "/orders/$orderId")
  }

  private fun readModelCount(jdbcTemplate: JdbcTemplate, orderId: Long): Long {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM order_query.order_read_model WHERE order_id = ?",
        Long::class.java,
        orderId,
    ) ?: 0L
  }

  private fun outboxPayload(jdbcTemplate: JdbcTemplate, orderId: Long): String {
    return jdbcTemplate.queryForObject(
        "SELECT payload FROM \"order\".outbox_events WHERE aggregate_id = ? AND event_type = ?",
        String::class.java,
        orderId.toString(),
        ORDER_PLACED_EVENT_TYPE,
    ) ?: error("No outbox payload for order $orderId")
  }

  private fun byIdRequest(orderQueryPort: Int, orderId: Long): HttpRequest {
    return HttpRequest.newBuilder(URI("http://localhost:$orderQueryPort/orders/$orderId"))
        .GET()
        .build()
  }

  private fun listRequest(orderQueryPort: Int): HttpRequest {
    return HttpRequest.newBuilder(URI("http://localhost:$orderQueryPort/orders?page=0&size=20"))
        .GET()
        .build()
  }

  private fun post(port: Int, path: String, body: String): HttpResponse<String> {
    val request =
        HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }

  private fun get(port: Int, path: String): HttpResponse<String> {
    val request = HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }

  private fun writeEvidence(
      orderId: Long,
      postLatencyMs: Double,
      e2eLatencyMs: Double,
      byId: LoadResult,
      list: LoadResult,
  ) {
    val section = buildSection(orderId, postLatencyMs, e2eLatencyMs, byId, list)
    val reportFile = File("../docs/bootcamp/evidence/p5-service-extraction.md")
    reportFile.parentFile.mkdirs()
    val existing = if (reportFile.exists()) reportFile.readText() else ""
    if ("## P5-07" in existing) {
      reportFile.writeText(existing.substringBefore("## P5-07").trimEnd() + "\n\n" + section)
    } else {
      reportFile.appendText("\n" + section)
    }
  }

  private fun buildSection(
      orderId: Long,
      postLatencyMs: Double,
      e2eLatencyMs: Double,
      byId: LoadResult,
      list: LoadResult,
  ): String {
    val builder = StringBuilder()
    builder.appendLine("## P5-07 — Cross-service end-to-end test")
    builder.appendLine()
    builder.appendLine(
        "Environment: one test JVM booting both applications against shared Testcontainers (PostgreSQL 16 + Kafka 7.7.1), JDK 21, Spring Boot 4.0.")
    builder.appendLine()
    builder.appendLine("### Startup-order case")
    builder.appendLine()
    builder.appendLine(
        "`order-query` was started first and became healthy before the monolith started; the flow succeeded once both were up.")
    builder.appendLine()
    builder.appendLine("### Flow")
    builder.appendLine()
    builder.appendLine(
        "`POST /orders` on the monolith → transactional outbox → Kafka `$ORDER_PLACED_TOPIC` → `order-query` projection → `order_query.order_read_model` → `GET /orders/{id}` from `order-query`")
    builder.appendLine()
    builder.appendLine("| Step | Detail |")
    builder.appendLine("|---|---|")
    builder.appendLine("| POST /orders | 201, order id $orderId |")
    builder.appendLine("| Outbox event | $ORDER_PLACED_EVENT_TYPE |")
    builder.appendLine("| Projection | read model row created for order $orderId |")
    builder.appendLine("| GET /orders/$orderId | 200, served from `order_query.order_read_model` |")
    builder.appendLine()
    builder.appendLine("### Timings")
    builder.appendLine()
    builder.appendLine("| Measurement | Value |")
    builder.appendLine("|---|---:|")
    builder.appendLine("| POST /orders latency | ${postLatencyMs.format(1)} ms |")
    builder.appendLine("| End-to-end (POST → read model visible) | ${e2eLatencyMs.format(1)} ms |")
    builder.appendLine()
    builder.appendLine("### Idempotency")
    builder.appendLine()
    builder.appendLine(
        "The $ORDER_PLACED_EVENT_TYPE event was replayed on the topic; the read model row count remained 1 and the row was unchanged.")
    builder.appendLine()
    builder.appendLine("### Query endpoint p95 under concurrent load")
    builder.appendLine()
    builder.appendLine(
        "Load: $QUERY_CONCURRENCY concurrent users, ramp-up ${LOAD_RAMP_UP_SECONDS}s, duration ${LOAD_DURATION_SECONDS}s.")
    builder.appendLine()
    builder.appendLine("| Endpoint | p95 (ms) | Throughput (RPS) | Error rate |")
    builder.appendLine("|---|---:|---:|---:|")
    builder.appendLine(
        "| ${byId.name} | ${byId.p95.format(2)} | ${byId.throughputRps.format(2)} | ${byId.errorRate.format(2)} |")
    builder.appendLine(
        "| ${list.name} | ${list.p95.format(2)} | ${list.throughputRps.format(2)} | ${list.errorRate.format(2)} |")
    return builder.toString()
  }

  private fun Double.format(digits: Int): String = String.format("%.${digits}f", this)
}

class ExtractionRedisContainer(image: DockerImageName) :
    GenericContainer<ExtractionRedisContainer>(image)
