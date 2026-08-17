package com.hyperscale.commerce.orderquery

import com.hyperscale.commerce.orderquery.application.OrderCancelledProjection
import com.hyperscale.commerce.orderquery.application.OrderPlacedProjection
import com.hyperscale.commerce.orderquery.application.OrderQueryService
import com.hyperscale.commerce.orderquery.jooq.order_query.Tables.ORDER_READ_MODEL
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.kafka.support.Acknowledgment
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrderOutOfOrderProjectionIntegrationTest
@Autowired
constructor(
    private val dsl: DSLContext,
    private val orderPlacedProjection: OrderPlacedProjection,
    private val orderCancelledProjection: OrderCancelledProjection,
    private val orderQueryService: OrderQueryService,
) {

  companion object {
    @Container
    @JvmStatic
    @ServiceConnection
    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
  }

  @BeforeEach
  fun clean() {
    dsl.deleteFrom(ORDER_READ_MODEL).execute()
    orderQueryService.evictAll()
  }

  @Test
  fun `out of order event arrival does not overwrite newer aggregate version state`() {
    val orderId = 88888L
    val ack = mock(Acknowledgment::class.java)

    // Step 1: Initial OrderPlaced event arrives with version 1
    val initialPlacedJson =
        """
        {
          "version": 1,
          "eventId": "evt-1",
          "orderId": $orderId,
          "status": "PLACED",
          "createdAt": "${Instant.now()}",
          "items": [{"sku": "SKU-TEST-1", "quantity": 1}],
          "aggregateVersion": 1
        }
        """
            .trimIndent()
    orderPlacedProjection.onOrderPlaced(initialPlacedJson, ack)

    var order = orderQueryService.getOrder(orderId)
    assertThat(order.status).isEqualTo("PLACED")

    // Step 2: OrderCancelled event arrives with version 2
    val cancelledJson =
        """
        {
          "version": 1,
          "eventId": "evt-2",
          "orderId": $orderId,
          "reason": "Customer cancellation",
          "createdAt": "${Instant.now()}",
          "aggregateVersion": 2
        }
        """
            .trimIndent()
    orderCancelledProjection.onOrderCancelled(cancelledJson, ack)

    order = orderQueryService.getOrder(orderId)
    assertThat(order.status).isEqualTo("CANCELLED")

    // Step 3: Delayed replayed / out-of-order OrderPlaced event (version 1) arrives
    val delayedPlacedJson =
        """
        {
          "version": 1,
          "eventId": "evt-1-delayed",
          "orderId": $orderId,
          "status": "PLACED",
          "createdAt": "${Instant.now()}",
          "items": [{"sku": "SKU-TEST-1", "quantity": 1}],
          "aggregateVersion": 1
        }
        """
            .trimIndent()
    orderPlacedProjection.onOrderPlaced(delayedPlacedJson, ack)

    // Verify: The read model MUST NOT regress back to PLACED!
    order = orderQueryService.getOrder(orderId)
    assertThat(order.status).isEqualTo("CANCELLED")

    val versionInDb =
        dsl.select(ORDER_READ_MODEL.VERSION)
            .from(ORDER_READ_MODEL)
            .where(ORDER_READ_MODEL.ORDER_ID.eq(orderId))
            .fetchOne(ORDER_READ_MODEL.VERSION)
    assertThat(versionInDb).isEqualTo(2L)
  }

  @Test
  fun `cancellation arriving before placement preserves cancellation and later enriches items`() {
    val orderId = 99999L
    val ack = mock(Acknowledgment::class.java)
    val cancellationTime = Instant.now()
    val placementTime = cancellationTime.minusSeconds(1)

    orderCancelledProjection.onOrderCancelled(
        """
        {
          "version": 1,
          "eventId": "evt-cancel-first",
          "orderId": $orderId,
          "reason": "Inventory unavailable",
          "createdAt": "$cancellationTime",
          "aggregateVersion": 2
        }
        """
            .trimIndent(),
        ack,
    )

    assertThat(orderQueryService.getOrder(orderId).status).isEqualTo("CANCELLED")
    assertThat(orderQueryService.getOrder(orderId).items).isEmpty()

    orderPlacedProjection.onOrderPlaced(
        """
        {
          "version": 1,
          "eventId": "evt-place-late",
          "orderId": $orderId,
          "status": "PLACED",
          "createdAt": "$placementTime",
          "items": [{"sku": "SKU-TEST-1", "quantity": 1}],
          "aggregateVersion": 1
        }
        """
            .trimIndent(),
        ack,
    )

    val result = orderQueryService.getOrder(orderId)
    assertThat(result.status).isEqualTo("CANCELLED")
    assertThat(result.items).hasSize(1)
    assertThat(result.items.single().sku).isEqualTo("SKU-TEST-1")
    assertThat(
            dsl.select(ORDER_READ_MODEL.VERSION)
                .from(ORDER_READ_MODEL)
                .where(ORDER_READ_MODEL.ORDER_ID.eq(orderId))
                .fetchOne(ORDER_READ_MODEL.VERSION))
        .isEqualTo(2L)
  }
}
