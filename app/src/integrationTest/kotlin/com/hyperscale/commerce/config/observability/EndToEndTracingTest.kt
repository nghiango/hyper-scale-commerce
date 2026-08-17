package com.hyperscale.commerce.config.observability

import com.fasterxml.jackson.databind.ObjectMapper
import com.hyperscale.commerce.Application
import com.hyperscale.commerce.orderquery.OrderQueryApplication
import com.hyperscale.commerce.resilience.ResilienceHarness
import com.hyperscale.commerce.testsupport.IsolatedRedisContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

private const val SKU = "PERF-SKU-00001"
private const val QUANTITY = 2
private const val TIMEOUT_SECONDS = 30L
private const val POLL_MILLIS = 500L
private const val KAFKA_HOST_PORT = 29098
private const val KAFKA_CONTAINER_PORT = 9093
private const val POSTGRES_HOST_PORT = 5438
private const val POSTGRES_CONTAINER_PORT = 5432

@Testcontainers
@ExtendWith(OutputCaptureExtension::class)
class EndToEndTracingTest {
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
  fun `trace id flows from post to order query consumer`(output: CapturedOutput) {
    val monolithContext = startMonolith()
    val monolithPort = monolithContext.environment.getProperty("local.server.port")!!.toInt()
    val orderQueryContext = startOrderQuery()
    val orderQueryPort = orderQueryContext.environment.getProperty("local.server.port")!!.toInt()

    try {
      ResilienceHarness.awaitReadiness(monolithPort)
      ResilienceHarness.awaitReadiness(orderQueryPort)

      val orderResponse = postOrder(monolithPort)
      assertThat(orderResponse.statusCode()).isEqualTo(201)
      val orderId = objectMapper.readTree(orderResponse.body()).get("id").asLong()
      val postTraceId =
          orderResponse.headers().firstValue(CorrelationIdFilter.TRACE_ID_HEADER).orElseThrow()

      awaitOrder(orderQueryPort, orderId)

      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
      var found = false
      while (System.nanoTime() < deadline) {
        if (output.all.contains(postTraceId) &&
            output.all.contains("Consumed record from order-placed traceId=$postTraceId")) {
          found = true
          break
        }
        Thread.sleep(POLL_MILLIS)
      }
      assertThat(found)
          .withFailMessage { "Trace $postTraceId was not propagated to order-query logs" }
          .isTrue
    } finally {
      monolithContext.close()
      orderQueryContext.close()
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
            "--spring.kafka.bootstrap-servers=${kafka.bootstrapServers}",
            "--spring.data.redis.host=${redis.host}",
            "--spring.data.redis.port=${redis.getMappedPort(6379)}",
            "--spring.flyway.locations=classpath:db/migration",
            "--spring.flyway.schemas=public",
            "--server.port=0",
        )
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

  private fun postOrder(port: Int): HttpResponse<String> {
    val request =
        HttpRequest.newBuilder(URI("http://localhost:$port/orders"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """{"items":[{"sku":"$SKU","quantity":$QUANTITY}]}"""))
            .build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }

  private fun awaitOrder(port: Int, orderId: Long) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      val request =
          HttpRequest.newBuilder(URI("http://localhost:$port/orders/$orderId")).GET().build()
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      if (response.statusCode() == 200) {
        return
      }
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError("Order $orderId not visible in read model within $TIMEOUT_SECONDS seconds")
  }
}
