package com.hyperscale.commerce.resilience

import com.fasterxml.jackson.databind.ObjectMapper
import com.hyperscale.commerce.Application
import com.hyperscale.commerce.orderquery.OrderQueryApplication
import com.hyperscale.commerce.testsupport.IsolatedRedisContainer
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
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

private const val SKU = "PERF-SKU-00001"
private const val QUANTITY = 2
private const val TIMEOUT_SECONDS = 30L
private const val POLL_MILLIS = 500L
private const val NANOS_PER_MILLISECOND = 1_000_000.0
private const val ORDER_PLACED_EVENT_TYPE = "OrderPlaced"
private const val KAFKA_HOST_PORT = 29093
private const val KAFKA_CONTAINER_PORT = 9093
private const val POSTGRES_HOST_PORT = 5433
private const val POSTGRES_CONTAINER_PORT = 5432

@Testcontainers
class KafkaOutageIntegrationTest {
  private val httpClient = HttpClient.newHttpClient()
  private val objectMapper = ObjectMapper()

  companion object {
    @Container
    @JvmStatic
    val kafka: KafkaContainer =
        KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1")).apply {
          setPortBindings(listOf("$KAFKA_HOST_PORT:$KAFKA_CONTAINER_PORT"))
        }

    @Container
    @JvmStatic
    val postgres: PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:16").apply {
          setPortBindings(listOf("$POSTGRES_HOST_PORT:$POSTGRES_CONTAINER_PORT"))
        }

    @Container
    @JvmStatic
    val redis =
        IsolatedRedisContainer(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379)
  }

  @Test
  fun `outbox buffers events while kafka is down and the projection catches up when the broker returns`() {
    val orderQueryContext = startOrderQuery()
    val orderQueryPort = orderQueryContext.environment.getProperty("local.server.port")!!.toInt()
    ResilienceHarness.awaitReadiness(orderQueryPort)

    val monolithContext = startMonolith()
    val monolithPort = monolithContext.environment.getProperty("local.server.port")!!.toInt()
    ResilienceHarness.awaitReadiness(monolithPort)

    try {
      val order1 = postOrder(monolithPort)
      awaitOrder(orderQueryPort, order1)

      ResilienceHarness.stopKafka(kafka)
      ResilienceHarness.awaitHealthContains(monolithPort, "\"kafka\":{\"status\":\"DOWN\"}")
      ResilienceHarness.awaitHealthContains(orderQueryPort, "\"kafka\":{\"status\":\"DOWN\"}")

      val order2 = postOrder(monolithPort)
      val monolithJdbc = monolithContext.getBean(JdbcTemplate::class.java)
      assertThat(outboxPublished(monolithJdbc, order2)).isFalse()

      ResilienceHarness.startKafka(kafka)
      ResilienceHarness.awaitHealthContains(monolithPort, "\"kafka\":{\"status\":\"UP\"}")
      ResilienceHarness.awaitHealthContains(orderQueryPort, "\"kafka\":{\"status\":\"UP\"}")

      awaitOrder(orderQueryPort, order2)
      assertThat(outboxPublished(monolithJdbc, order2)).isTrue()

      ResilienceHarness.writeEvidence(buildSection(order1, order2))
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

  private fun postOrder(port: Int): Long {
    val start = System.nanoTime()
    val response =
        post(
            port,
            "/orders",
            """{"items":[{"sku":"$SKU","quantity":$QUANTITY}]}""",
        )
    val latency = (System.nanoTime() - start).toDouble() / NANOS_PER_MILLISECOND
    assertThat(response.statusCode()).isEqualTo(201)
    val orderId = objectMapper.readTree(response.body()).get("id").asLong()
    assertThat(objectMapper.readTree(response.body()).get("status").asText()).isEqualTo("PLACED")
    return orderId
  }

  private fun awaitOrder(port: Int, orderId: Long) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      val response = get(port, "/orders/$orderId")
      if (response.statusCode() == 200) {
        val body = objectMapper.readTree(response.body())
        assertThat(body.get("id").asLong()).isEqualTo(orderId)
        assertThat(body.get("status").asText()).isEqualTo("PLACED")
        return
      }
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError("Order $orderId not visible in read model within $TIMEOUT_SECONDS seconds")
  }

  private fun outboxPublished(jdbcTemplate: JdbcTemplate, orderId: Long): Boolean {
    val count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM \"order\".outbox_events WHERE aggregate_id = ? AND event_type = ? AND published_at IS NOT NULL",
            Long::class.java,
            orderId.toString(),
            ORDER_PLACED_EVENT_TYPE,
        )
    return (count ?: 0L) > 0
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

  private fun buildSection(order1: Long, order2: Long): String {
    return buildString {
      appendLine("## P6-03 — Kafka outage experiments")
      appendLine()
      appendLine(
          "Environment: one test JVM booting both applications against shared Testcontainers (PostgreSQL 16 + Kafka 7.7.1), JDK 21, Spring Boot 4.0.")
      appendLine()
      appendLine("### Experiment A: projection side")
      appendLine()
      appendLine(
          "`order-query` was started while Kafka was up, then the broker was stopped and restarted. The projection caught up to the events published after the broker returned.")
      appendLine()
      appendLine("### Experiment B: outbox side")
      appendLine()
      appendLine(
          "`POST /orders` while Kafka was down buffered the event in the outbox (`published = false`). After the broker returned, the relay published the event and the projection consumed it.")
      appendLine()
      appendLine("| Order | Kafka state | Visible in read model | Outbox published |")
      appendLine("|---|---|---|---:|")
      appendLine("| $order1 | UP | yes | yes |")
      appendLine("| $order2 | DOWN then UP | yes (after recovery) | yes (after recovery) |")
    }
  }
}
