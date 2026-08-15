package com.hyperscale.commerce.resilience

import com.fasterxml.jackson.databind.ObjectMapper
import com.hyperscale.commerce.Application
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

private const val SKU = "PERF-SKU-00001"
private const val QUANTITY = 2
private const val TIMEOUT_SECONDS = 30L
private const val POLL_MILLIS = 500L
private const val ORDER_PLACED_EVENT_TYPE = "OrderPlaced"
private const val KAFKA_HOST_PORT = 29094
private const val KAFKA_CONTAINER_PORT = 9093
private const val POSTGRES_HOST_PORT = 5434
private const val POSTGRES_CONTAINER_PORT = 5432

@Testcontainers
class PostgresOutageIntegrationTest {
  private val httpClient = HttpClient.newHttpClient()
  private val objectMapper = ObjectMapper()

  companion object {
    @JvmStatic private val postgresDataDir = Files.createTempDirectory("p6-04-app-postgres-")

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
          withFileSystemBind(
              postgresDataDir.toString(), "/var/lib/postgresql/data", BindMode.READ_WRITE)
          waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2))
        }
  }

  @Test
  fun `app survives a postgres outage without losing committed data`() {
    val monolithContext = startMonolith()
    val monolithPort = monolithContext.environment.getProperty("local.server.port")!!.toInt()

    try {
      ResilienceHarness.awaitReadiness(monolithPort)

      val order1 = postOrder(monolithPort)
      awaitEventsPublished(monolithPort, 1.0)
      val jdbcTemplate = monolithContext.getBean(JdbcTemplate::class.java)
      awaitOutboxPublished(jdbcTemplate, order1)

      ResilienceHarness.stopPostgres(postgres)
      ResilienceHarness.awaitHealthContains(monolithPort, "\"db\":{\"status\":\"DOWN\"}")

      val failingResponse =
          post(monolithPort, "/orders", """{"items":[{"sku":"$SKU","quantity":$QUANTITY}]}""")
      assertThat(failingResponse.statusCode()).isGreaterThanOrEqualTo(500)

      ResilienceHarness.startPostgres(postgres)
      ResilienceHarness.awaitHealthContains(monolithPort, "\"db\":{\"status\":\"UP\"}")

      awaitOutboxPublished(jdbcTemplate, order1)
      assertThat(eventsPublished(monolithPort)).isGreaterThanOrEqualTo(1.0)
      val order2 = postOrder(monolithPort)
      awaitEventsPublished(monolithPort, 2.0)

      ResilienceHarness.writeEvidence(buildSection(order1, order2))
    } finally {
      monolithContext.close()
    }
  }

  private fun startMonolith(): ConfigurableApplicationContext {
    return SpringApplicationBuilder(Application::class.java)
        .run(
            "--spring.application.name=hyper-scale-commerce",
            "--app.name=hyper-scale-commerce",
            "--spring.datasource.url=${postgres.jdbcUrl}",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--spring.datasource.hikari.connection-test-query=SELECT 1",
            "--spring.datasource.hikari.validation-timeout=2000",
            "--spring.kafka.bootstrap-servers=${kafka.bootstrapServers}",
            "--spring.flyway.locations=classpath:db/migration",
            "--spring.flyway.schemas=public",
            "--server.port=0",
        )
  }

  private fun postOrder(port: Int): Long {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      val response = post(port, "/orders", """{"items":[{"sku":"$SKU","quantity":$QUANTITY}]}""")
      if (response.statusCode() == 201) {
        val orderId = objectMapper.readTree(response.body()).get("id").asLong()
        assertThat(objectMapper.readTree(response.body()).get("status").asText())
            .isEqualTo("PLACED")
        return orderId
      }
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError("Could not create an order within $TIMEOUT_SECONDS seconds")
  }

  private fun awaitEventsPublished(port: Int, expected: Double) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      if (eventsPublished(port) >= expected) {
        return
      }
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError(
        "events_published_total did not reach $expected within $TIMEOUT_SECONDS seconds")
  }

  private fun eventsPublished(port: Int): Double {
    val response = get(port, "/actuator/prometheus")
    assertThat(response.statusCode()).isEqualTo(200)
    val match =
        Regex("""events_published_total\{[^}]*topic=\"order-placed\"[^}]*\}\s+(\d+\.?\d*)""")
            .find(response.body())
    return match?.groupValues?.get(1)?.toDouble() ?: 0.0
  }

  private fun awaitOutboxPublished(jdbcTemplate: JdbcTemplate, orderId: Long) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      val count =
          jdbcTemplate.queryForObject(
              "SELECT count(*) FROM \"order\".outbox_events WHERE aggregate_id = ? AND event_type = ? AND published_at IS NOT NULL",
              Long::class.java,
              orderId.toString(),
              ORDER_PLACED_EVENT_TYPE,
          ) ?: 0L
      if (count == 1L) {
        return
      }
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError("Committed outbox event for order $orderId was not published")
  }

  private fun get(port: Int, path: String): HttpResponse<String> {
    val request = HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }

  private fun post(port: Int, path: String, body: String): HttpResponse<String> {
    val request =
        HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }

  private fun buildSection(order1: Long, order2: Long): String {
    return buildString {
      appendLine("## P6-04 — PostgreSQL outage experiments")
      appendLine()
      appendLine(
          "Environment: Testcontainers PostgreSQL 16 + Kafka 7.7.1 with fixed host ports, JDK 21, Spring Boot 4.0.")
      appendLine()
      appendLine("### Experiment A: application side")
      appendLine()
      appendLine(
          "The monolith was started with PostgreSQL up. `POST /orders` for order $order1 committed the row and the outbox was published. PostgreSQL was stopped; a subsequent `POST /orders` returned a 5xx error. After the database returned, the committed outbox row for order $order1 was still present, and order $order2 could be created and published.")
      appendLine()
      appendLine("| Order | Postgres state | POST accepted | Outbox published |")
      appendLine("|---|---|---:|:---:|")
      appendLine("| $order1 | UP | yes | yes |")
      appendLine("| (new) | DOWN | no | — |")
      appendLine("| $order2 | UP after recovery | yes | yes |")
    }
  }
}
