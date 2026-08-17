package com.hyperscale.commerce.orderquery.admin

import com.hyperscale.commerce.orderquery.application.DlqReplayService
import com.hyperscale.commerce.orderquery.application.OrderQueryService
import com.hyperscale.commerce.orderquery.jooq.order_query.Tables.ORDER_READ_MODEL
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.producer.ProducerRecord
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DlqReplayIntegrationTest
@Autowired
constructor(
    private val dlqReplayService: DlqReplayService,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val orderQueryService: OrderQueryService,
    private val dsl: DSLContext,
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

  @BeforeEach
  fun clean() {
    dsl.deleteFrom(ORDER_READ_MODEL).execute()
    orderQueryService.evictAll()
  }

  @Test
  fun `replays dead-lettered event to target topic and projection processes it successfully`() {
    val orderId = 77777L
    val dlqTopic = "order-placed-dlq"
    val targetTopic = "order-placed"

    val validPayload =
        """
        {
          "version": 1,
          "eventId": "evt-dlq-77777",
          "orderId": $orderId,
          "status": "PLACED",
          "createdAt": "${Instant.now()}",
          "items": [{"sku": "SKU-REPLAY-1", "quantity": 3}],
          "aggregateVersion": 1
        }
        """
            .trimIndent()

    // 1. Send event to DLQ topic
    kafkaTemplate
        .send(ProducerRecord(dlqTopic, orderId.toString(), validPayload))
        .get(5, TimeUnit.SECONDS)

    // 2. Trigger DLQ Replay
    val result = dlqReplayService.replay(dlqTopic, targetTopic, 10)
    assertThat(result.replayedCount).isGreaterThanOrEqualTo(1)
    assertThat(result.skippedCount).isEqualTo(0)

    // 3. Await projection consumption
    awaitProjection(orderId)

    val order = orderQueryService.getOrder(orderId)
    assertThat(order.status).isEqualTo("PLACED")
    assertThat(order.items).hasSize(1)
    assertThat(order.items[0].sku).isEqualTo("SKU-REPLAY-1")
    assertThat(order.items[0].quantity).isEqualTo(3)
  }

  private fun awaitProjection(orderId: Long) {
    val deadline = System.currentTimeMillis() + 10000
    while (System.currentTimeMillis() < deadline) {
      val exists =
          dsl.selectCount()
              .from(ORDER_READ_MODEL)
              .where(ORDER_READ_MODEL.ORDER_ID.eq(orderId))
              .fetchOne(0, Long::class.java) ?: 0L
      if (exists > 0) return
      Thread.sleep(200)
    }
    throw AssertionError("Order $orderId was not projected after DLQ replay within timeout")
  }
}
