package com.hyperscale.commerce.orderquery.resilience

import com.hyperscale.commerce.orderquery.OrderQueryApplication
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Instant
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

private const val SKU = "PERF-SKU-00001"
private const val TIMEOUT_SECONDS = 30L
private const val POLL_MILLIS = 500L
private const val ORDER_PLACED_TOPIC = "order-placed"
private const val KAFKA_HOST_PORT = 29095
private const val KAFKA_CONTAINER_PORT = 9093
private const val POSTGRES_HOST_PORT = 5435
private const val POSTGRES_CONTAINER_PORT = 5432

@Testcontainers
class PostgresOutageIntegrationTest {
  private val httpClient = HttpClient.newHttpClient()

  companion object {
    @JvmStatic
    private val postgresDataDir = Files.createTempDirectory("p6-04-order-query-postgres-")

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
  fun `order-query survives a postgres outage and catches up`() {
    val context = startOrderQuery()
    val port = context.environment.getProperty("local.server.port")!!.toInt()

    try {
      ResilienceHarness.awaitReadiness(port)
      val producerBootstrap = "localhost:$KAFKA_HOST_PORT"

      val order1 = 1L
      produceOrderPlaced(order1, producerBootstrap)
      awaitOrder(port, order1)
      assertThat(get(port, "/orders/$order1").statusCode()).isEqualTo(200)

      ResilienceHarness.stopPostgres(postgres)
      ResilienceHarness.awaitHealthContains(port, "\"DOWN\"", path = "/actuator/health/readiness")
      val uncachedOrder = 99999L
      assertThat(get(port, "/orders/$uncachedOrder").statusCode()).isGreaterThanOrEqualTo(500)

      val order2 = 2L
      produceOrderPlaced(order2, producerBootstrap)

      ResilienceHarness.startPostgres(postgres)
      ResilienceHarness.awaitHealthContains(port, "\"UP\"", path = "/actuator/health/readiness")
      awaitOrder(port, order1)
      assertThat(get(port, "/orders/$order1").statusCode()).isEqualTo(200)
      awaitOrder(port, order2)
      assertThat(get(port, "/orders/$order2").statusCode()).isEqualTo(200)
    } finally {
      context.close()
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
            "--spring.kafka.bootstrap-servers=PLAINTEXT://localhost:$KAFKA_HOST_PORT",
            "--spring.flyway.locations=classpath:db/migration-order-query",
            "--spring.flyway.schemas=order_query",
            "--server.port=0",
        )
  }

  private fun produceOrderPlaced(orderId: Long, bootstrapServers: String) {
    val props = Properties()
    props[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = bootstrapServers
    props[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java.name
    props[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java.name
    KafkaProducer<String, String>(props).use { producer ->
      val event =
          """{"version":1,"eventId":"p6-04-$orderId","orderId":$orderId,"status":"PLACED","createdAt":"${Instant.now()}","items":[{"sku":"$SKU","quantity":2}]}"""
      producer.send(ProducerRecord(ORDER_PLACED_TOPIC, orderId.toString(), event)).get()
    }
  }

  private fun awaitOrder(port: Int, orderId: Long) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      if (get(port, "/orders/$orderId").statusCode() == 200) {
        return
      }
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError("Order $orderId not projected within $TIMEOUT_SECONDS seconds")
  }

  private fun get(port: Int, path: String): HttpResponse<String> {
    val request = HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }
}
