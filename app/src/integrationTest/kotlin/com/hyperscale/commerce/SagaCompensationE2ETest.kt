package com.hyperscale.commerce

import com.fasterxml.jackson.databind.ObjectMapper
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
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

private const val OOS_SKU = "SKU-OOS-OUT-OF-STOCK"
private const val QUANTITY = 1
private const val TIMEOUT_SECONDS = 60L
private const val POLL_MILLIS = 300L
private const val READINESS_RETRY_MILLIS = 500L

@Testcontainers
class SagaCompensationE2ETest {
  private val httpClient = HttpClient.newHttpClient()
  private val objectMapper = ObjectMapper()

  companion object {
    @Container
    @JvmStatic
    val kafka: KafkaContainer = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))

    @Container @JvmStatic val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

    @Container
    @JvmStatic
    val redis = SagaRedisContainer(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379)
  }

  @Test
  fun `out of stock reservation triggers saga compensation and cancels order`() {
    val orderQueryContext = startOrderQuery()
    val orderQueryPort = orderQueryContext.environment.getProperty("local.server.port")!!.toInt()
    awaitReadiness(orderQueryPort)

    val monolithContext = startMonolith()
    val monolithPort = monolithContext.environment.getProperty("local.server.port")!!.toInt()
    awaitReadiness(monolithPort)

    try {
      val postResponse =
          post(
              monolithPort,
              "/orders",
              """{"items":[{"sku":"$OOS_SKU","quantity":$QUANTITY}]}""",
          )
      assertThat(postResponse.statusCode()).isEqualTo(201)
      val created = objectMapper.readTree(postResponse.body())
      val orderId = created.get("id").asLong()
      assertThat(created.get("status").asText()).isEqualTo("PLACED")

      val cancelledOrder = awaitCancelledOrder(orderQueryPort, orderId)
      val body = objectMapper.readTree(cancelledOrder.body())
      assertThat(body.get("id").asLong()).isEqualTo(orderId)
      assertThat(body.get("status").asText()).isEqualTo("CANCELLED")
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

  private fun awaitCancelledOrder(orderQueryPort: Int, orderId: Long): HttpResponse<String> {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      val response = get(orderQueryPort, "/orders/$orderId")
      if (response.statusCode() == 200) {
        val body = objectMapper.readTree(response.body())
        if (body.get("status").asText() == "CANCELLED") {
          return response
        }
      }
      Thread.sleep(POLL_MILLIS)
    }
    return get(orderQueryPort, "/orders/$orderId")
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

class SagaRedisContainer(image: DockerImageName) : GenericContainer<SagaRedisContainer>(image)
