package com.hyperscale.commerce.modules.inventory

import com.hyperscale.commerce.config.OutboxProperties
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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

private const val EVENT_ID = "event-1"
private const val ORDER_ID = 42L
private const val SKU = "PERF-SKU-00001"
private const val QUANTITY = 2
private const val TIMEOUT_SECONDS = 15L
private const val POLL_MILLIS = 500L
private const val GRACE_SECONDS = 3L

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class InventoryConsumerIntegrationTest
@Autowired
constructor(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val jdbcTemplate: JdbcTemplate,
    private val outboxProperties: OutboxProperties,
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
  fun `consumes OrderPlaced and records reservations idempotently`() {
    val payload =
        """{"version":1,"eventId":"$EVENT_ID","orderId":$ORDER_ID,"items":[{"sku":"$SKU","quantity":$QUANTITY}]}"""

    kafkaTemplate
        .send(outboxProperties.topic, ORDER_ID.toString(), payload)
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    awaitReservationCount(1)

    kafkaTemplate
        .send(outboxProperties.topic, ORDER_ID.toString(), payload)
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GRACE_SECONDS)
    while (System.nanoTime() < deadline) {
      assertThat(reservationCount()).isLessThanOrEqualTo(1)
      Thread.sleep(POLL_MILLIS)
    }
    assertThat(reservationCount()).isEqualTo(1)
  }

  private fun awaitReservationCount(expected: Long) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      if (reservationCount() == expected) {
        return
      }
      Thread.sleep(POLL_MILLIS)
    }
    assertThat(reservationCount()).isEqualTo(expected)
  }

  private fun reservationCount(): Long {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM inventory.reservations WHERE event_id = ?",
        Long::class.java,
        EVENT_ID,
    ) ?: 0L
  }
}
