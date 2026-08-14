package com.hyperscale.commerce.orderquery

import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

private const val EVENT_ID = "event-1"
private const val ORDER_ID = 42L
private const val STATUS = "PLACED"
private const val CREATED_AT = "2026-08-14T00:00:00Z"
private const val SKU = "PERF-SKU-00001"
private const val QUANTITY = 2
private const val TIMEOUT_SECONDS = 15L
private const val POLL_MILLIS = 500L
private const val GRACE_SECONDS = 3L

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrderProjectionIntegrationTest
@Autowired
constructor(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val jdbcTemplate: JdbcTemplate,
    @param:Value("\${app.outbox.topic}") private val topic: String,
) {

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
  fun `projects OrderPlaced into the read model idempotently`() {
    val payload =
        """{"version":1,"eventId":"$EVENT_ID","orderId":$ORDER_ID,"status":"$STATUS","createdAt":"$CREATED_AT","items":[{"sku":"$SKU","quantity":$QUANTITY}]}"""

    kafkaTemplate.send(topic, ORDER_ID.toString(), payload).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    awaitReadModelCount(1)
    assertThat(readModelStatus()).isEqualTo(STATUS)
    assertThat(readModelItems()).contains(SKU)

    kafkaTemplate.send(topic, ORDER_ID.toString(), payload).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GRACE_SECONDS)
    while (System.nanoTime() < deadline) {
      assertThat(readModelCount()).isLessThanOrEqualTo(1)
      Thread.sleep(POLL_MILLIS)
    }
    assertThat(readModelCount()).isEqualTo(1)
    assertThat(readModelStatus()).isEqualTo(STATUS)
    assertThat(readModelItems()).contains(SKU)
  }

  private fun awaitReadModelCount(expected: Long) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      if (readModelCount() == expected) {
        return
      }
      Thread.sleep(POLL_MILLIS)
    }
    assertThat(readModelCount()).isEqualTo(expected)
  }

  private fun readModelCount(): Long {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM order_query.order_read_model WHERE order_id = ?",
        Long::class.java,
        ORDER_ID,
    ) ?: 0L
  }

  private fun readModelStatus(): String {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM order_query.order_read_model WHERE order_id = ?",
        String::class.java,
        ORDER_ID,
    ) ?: error("No read model row for order $ORDER_ID")
  }

  private fun readModelItems(): String {
    return jdbcTemplate.queryForObject(
        "SELECT items::text FROM order_query.order_read_model WHERE order_id = ?",
        String::class.java,
        ORDER_ID,
    ) ?: error("No read model row for order $ORDER_ID")
  }
}
