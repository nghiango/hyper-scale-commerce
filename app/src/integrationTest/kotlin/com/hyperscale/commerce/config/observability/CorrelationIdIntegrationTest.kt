package com.hyperscale.commerce.config.observability

import com.fasterxml.jackson.databind.ObjectMapper
import com.hyperscale.commerce.Application
import com.hyperscale.commerce.orderquery.OrderQueryApplication
import com.hyperscale.commerce.resilience.ResilienceHarness
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
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
private const val KAFKA_HOST_PORT = 29097
private const val KAFKA_CONTAINER_PORT = 9093
private const val POSTGRES_HOST_PORT = 5437
private const val POSTGRES_CONTAINER_PORT = 5432

@Testcontainers
class CorrelationIdIntegrationTest {
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
  fun `correlation id propagates through HTTP and Kafka`() {
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

      val postCorrelationId = orderResponse.headers().firstValue("X-Correlation-Id")
      assertThat(postCorrelationId).isPresent

      awaitOrder(orderQueryPort, orderId)
      val getResponse = get(orderQueryPort, "/orders/$orderId")
      assertThat(getResponse.statusCode()).isEqualTo(200)
      assertThat(getResponse.headers().firstValue("X-Correlation-Id")).isPresent

      val recordCorrelationId = consumeCorrelationId(orderId)
      assertThat(recordCorrelationId).isNotNull
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
      val response = get(port, "/orders/$orderId")
      if (response.statusCode() == 200) {
        return
      }
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError("Order $orderId not visible in read model within $TIMEOUT_SECONDS seconds")
  }

  private fun consumeCorrelationId(orderId: Long): String? {
    val props = Properties()
    props[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = kafka.bootstrapServers
    props[ConsumerConfig.GROUP_ID_CONFIG] = "correlation-test-${System.currentTimeMillis()}"
    props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
    props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java.name
    props[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java.name

    KafkaConsumer<String, String>(props).use { consumer ->
      consumer.subscribe(listOf("order-placed"))
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
      while (System.nanoTime() < deadline) {
        val records = consumer.poll(Duration.ofSeconds(1))
        val matching =
            records.records("order-placed").find { record -> record.key() == orderId.toString() }
        if (matching != null) {
          return matching
              .headers()
              .lastHeader(CorrelationIdRecordInterceptor.CORRELATION_ID_RECORD_HEADER)
              ?.value()
              ?.toString(Charsets.UTF_8)
        }
      }
    }
    return null
  }

  private fun get(port: Int, path: String): HttpResponse<String> {
    val request = HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }
}
