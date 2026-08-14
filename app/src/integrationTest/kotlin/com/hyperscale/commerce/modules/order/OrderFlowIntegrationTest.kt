package com.hyperscale.commerce.modules.order

import com.fasterxml.jackson.databind.ObjectMapper
import com.hyperscale.commerce.config.OutboxProperties
import java.io.File
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

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderFlowIntegrationTest
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
  fun `order flows end to end to an inventory reservation`() {
    val postStart = System.nanoTime()
    val response = post("/orders", """{"items":[{"sku":"$SKU","quantity":$QUANTITY}]}""")
    val postLatencyMs = (System.nanoTime() - postStart).toDouble() / NANOS_PER_MILLISECOND

    assertThat(response.statusCode()).isEqualTo(201)
    val orderId = objectMapper.readTree(response.body()).get("id").asLong()

    val e2eStart = System.nanoTime()
    val reservation = awaitReservation(orderId)
    val e2eLatencyMs = (System.nanoTime() - e2eStart).toDouble() / NANOS_PER_MILLISECOND

    assertThat(reservation.sku).isEqualTo(SKU)
    assertThat(reservation.quantity).isEqualTo(QUANTITY)
    assertThat(reservation.eventId).isNotBlank()

    val payload = outboxPayload(orderId)
    kafkaTemplate
        .send(outboxProperties.topic, orderId.toString(), payload)
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GRACE_SECONDS)
    while (System.nanoTime() < deadline) {
      assertThat(reservationCount(reservation.eventId)).isLessThanOrEqualTo(1)
      Thread.sleep(POLL_MILLIS)
    }
    assertThat(reservationCount(reservation.eventId)).isEqualTo(1)

    val report = buildReport(orderId, postLatencyMs, e2eLatencyMs, reservation.eventId)
    val reportFile = File("../docs/bootcamp/evidence/p3-event-flow.md")
    reportFile.parentFile.mkdirs()
    reportFile.writeText(report)
  }

  private fun awaitReservation(orderId: Long): ReservationRow {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      val rows =
          jdbcTemplate.query(
              "SELECT sku, quantity, event_id FROM inventory.reservations WHERE order_id = ?",
              { rs, _ ->
                ReservationRow(
                    sku = rs.getString("sku"),
                    quantity = rs.getInt("quantity"),
                    eventId = rs.getString("event_id"),
                )
              },
              orderId,
          )
      if (rows.isNotEmpty()) {
        return rows.first()
      }
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError("No reservation for order $orderId within $TIMEOUT_SECONDS seconds")
  }

  private fun reservationCount(eventId: String): Long {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM inventory.reservations WHERE event_id = ?",
        Long::class.java,
        eventId,
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

  private fun buildReport(
      orderId: Long,
      postLatencyMs: Double,
      e2eLatencyMs: Double,
      eventId: String,
  ): String {
    val builder = StringBuilder()
    builder.appendLine("# Phase 3 — Event-Driven Flow Report")
    builder.appendLine()
    builder.appendLine(
        "Environment: local Docker PostgreSQL 16 + Kafka (Testcontainers), JDK 21, Spring Boot 4.0.")
    builder.appendLine()
    builder.appendLine("## Flow")
    builder.appendLine()
    builder.appendLine(
        "`POST /orders` → transactional outbox → Kafka `${outboxProperties.topic}` → inventory reservation")
    builder.appendLine()
    builder.appendLine("| Step | Detail |")
    builder.appendLine("|---|---|")
    builder.appendLine("| POST /orders | 201, order id $orderId |")
    builder.appendLine("| Outbox event | $ORDER_PLACED_EVENT_TYPE, event id `$eventId` |")
    builder.appendLine("| Kafka topic | ${outboxProperties.topic} |")
    builder.appendLine("| Inventory reservation | sku $SKU, quantity $QUANTITY |")
    builder.appendLine()
    builder.appendLine("## Timings")
    builder.appendLine()
    builder.appendLine("| Measurement | Value |")
    builder.appendLine("|---|---:|")
    builder.appendLine("| POST /orders latency | ${postLatencyMs.format(1)} ms |")
    builder.appendLine("| End-to-end (POST → reservation) | ${e2eLatencyMs.format(1)} ms |")
    builder.appendLine()
    builder.appendLine("## Idempotency")
    builder.appendLine()
    builder.appendLine(
        "The $ORDER_PLACED_EVENT_TYPE event was replayed on the topic; the reservation count remained 1.")
    return builder.toString()
  }

  private fun Double.format(digits: Int): String = String.format("%.${digits}f", this)

  private fun post(path: String, body: String): HttpResponse<String> {
    val request =
        HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }
}

private data class ReservationRow(val sku: String, val quantity: Int, val eventId: String)
