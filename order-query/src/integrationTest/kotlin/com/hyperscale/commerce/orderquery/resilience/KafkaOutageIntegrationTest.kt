package com.hyperscale.commerce.orderquery.resilience

import com.hyperscale.commerce.orderquery.OrderQueryApplication
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
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

private const val TIMEOUT_SECONDS = 30L
private const val POLL_MILLIS = 500L
private const val ORDER_PLACED_TOPIC = "order-placed"
private const val SKU = "PERF-SKU-00001"
private const val KAFKA_HOST_PORT = 29093
private const val KAFKA_CONTAINER_PORT = 9093
private const val POSTGRES_HOST_PORT = 5433
private const val POSTGRES_CONTAINER_PORT = 5432

@Testcontainers
class KafkaOutageIntegrationTest {

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
  fun `starts without kafka and catches up when the broker returns`() {
    ResilienceHarness.stopKafka(kafka)

    val context = startOrderQuery()
    val port = context.environment.getProperty("local.server.port")!!.toInt()
    try {
      ResilienceHarness.awaitReadiness(port)
      ResilienceHarness.awaitHealthContains(port, "\"kafka\":{\"status\":\"DOWN\"}")

      ResilienceHarness.startKafka(kafka)
      ResilienceHarness.awaitHealthContains(port, "\"kafka\":{\"status\":\"UP\"}")

      val orderId = 1L
      val producerBootstrap = "localhost:$KAFKA_HOST_PORT"
      produceOrderPlaced(orderId, producerBootstrap)

      val jdbcTemplate = context.getBean(JdbcTemplate::class.java)
      awaitReadModel(jdbcTemplate, orderId)
      assertThat(readModelCount(jdbcTemplate, orderId)).isEqualTo(1)
    } finally {
      context.close()
    }
  }

  private fun startOrderQuery(): ConfigurableApplicationContext {
    val kafkaBootstrap = "PLAINTEXT://localhost:$KAFKA_HOST_PORT"
    return SpringApplicationBuilder(OrderQueryApplication::class.java)
        .run(
            "--spring.config.name=orderquery",
            "--spring.application.name=order-query",
            "--app.name=order-query",
            "--spring.datasource.url=${postgres.jdbcUrl}",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--spring.kafka.bootstrap-servers=$kafkaBootstrap",
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
          """{"version":1,"eventId":"p6-03-$orderId","orderId":$orderId,"status":"PLACED","createdAt":"${Instant.now()}","items":[{"sku":"$SKU","quantity":2}]}"""
      producer.send(ProducerRecord(ORDER_PLACED_TOPIC, orderId.toString(), event)).get()
    }
  }

  private fun awaitReadModel(jdbcTemplate: JdbcTemplate, orderId: Long) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      if (readModelCount(jdbcTemplate, orderId) == 1L) {
        return
      }
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError("Order $orderId not projected within $TIMEOUT_SECONDS seconds")
  }

  private fun readModelCount(jdbcTemplate: JdbcTemplate, orderId: Long): Long {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM order_query.order_read_model WHERE order_id = ?",
        Long::class.java,
        orderId,
    ) ?: 0L
  }
}
