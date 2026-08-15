package com.hyperscale.commerce.resilience

import com.fasterxml.jackson.databind.ObjectMapper
import com.hyperscale.commerce.Application
import com.hyperscale.commerce.orderquery.OrderQueryApplication
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
private const val KAFKA_HOST_PORT = 29096
private const val KAFKA_CONTAINER_PORT = 9093
private const val POSTGRES_HOST_PORT = 5436
private const val POSTGRES_CONTAINER_PORT = 5432

@Testcontainers
class PartialOutageIntegrationTest {
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
  }

  @Test
  fun `order-query down while app writes an order catches up when order-query starts`() {
    val monolithContext = startMonolith()
    val monolithPort = monolithContext.environment.getProperty("local.server.port")!!.toInt()
    ResilienceHarness.awaitReadiness(monolithPort)

    try {
      val orderId = postOrder(monolithPort)

      val monolithJdbc = monolithContext.getBean(JdbcTemplate::class.java)
      awaitOutboxPublished(monolithJdbc, orderId)

      val orderQueryContext = startOrderQuery()
      val orderQueryPort = orderQueryContext.environment.getProperty("local.server.port")!!.toInt()
      try {
        ResilienceHarness.awaitReadiness(orderQueryPort)
        awaitOrder(orderQueryPort, orderId)
      } finally {
        orderQueryContext.close()
      }
    } finally {
      monolithContext.close()
    }
  }

  @Test
  fun `app down after projection catches up and a new order flows when app restarts`() {
    val orderQueryContext = startOrderQuery()
    val orderQueryPort = orderQueryContext.environment.getProperty("local.server.port")!!.toInt()
    ResilienceHarness.awaitReadiness(orderQueryPort)

    val monolithContext = startMonolith()
    var monolithPort = monolithContext.environment.getProperty("local.server.port")!!.toInt()
    ResilienceHarness.awaitReadiness(monolithPort)

    try {
      val order1 = postOrder(monolithPort)
      awaitOrder(orderQueryPort, order1)

      monolithContext.close()

      assertThat(get(orderQueryPort, "/orders/$order1").statusCode()).isEqualTo(200)

      val restartedContext = startMonolith()
      monolithPort = restartedContext.environment.getProperty("local.server.port")!!.toInt()
      ResilienceHarness.awaitReadiness(monolithPort)

      try {
        val order2 = postOrder(monolithPort)
        awaitOrder(orderQueryPort, order2)
      } finally {
        restartedContext.close()
      }
    } finally {
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
            "--spring.flyway.locations=classpath:db/migration",
            "--spring.flyway.schemas=public",
            "--server.port=0",
        )
  }

  private fun postOrder(port: Int): Long {
    val response =
        post(
            port,
            "/orders",
            """{"items":[{"sku":"$SKU","quantity":$QUANTITY}]}""",
        )
    assertThat(response.statusCode()).isEqualTo(201)
    val body = objectMapper.readTree(response.body())
    assertThat(body.get("status").asText()).isEqualTo("PLACED")
    return body.get("id").asLong()
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

  private fun awaitOutboxPublished(jdbcTemplate: JdbcTemplate, orderId: Long) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      val count =
          jdbcTemplate.queryForObject(
              "SELECT count(*) FROM \"order\".outbox_events WHERE aggregate_id = ? AND event_type = ? AND published_at IS NOT NULL",
              Long::class.java,
              orderId.toString(),
              ORDER_PLACED_EVENT_TYPE,
          )
      if ((count ?: 0L) > 0) {
        return
      }
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError(
        "Outbox event for order $orderId not published within $TIMEOUT_SECONDS seconds")
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
}
