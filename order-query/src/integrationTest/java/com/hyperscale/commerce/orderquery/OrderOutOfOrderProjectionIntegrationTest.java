package com.hyperscale.commerce.orderquery;

import static com.hyperscale.commerce.orderquery.jooq.order_query.Tables.ORDER_READ_MODEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hyperscale.commerce.orderquery.application.OrderCancelledProjection;
import com.hyperscale.commerce.orderquery.application.OrderDto;
import com.hyperscale.commerce.orderquery.application.OrderPlacedProjection;
import com.hyperscale.commerce.orderquery.application.OrderQueryService;
import java.time.Instant;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.support.Acknowledgment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrderOutOfOrderProjectionIntegrationTest {

  private final DSLContext dsl;
  private final OrderPlacedProjection orderPlacedProjection;
  private final OrderCancelledProjection orderCancelledProjection;
  private final OrderQueryService orderQueryService;

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @Autowired
  OrderOutOfOrderProjectionIntegrationTest(
      DSLContext dsl,
      OrderPlacedProjection orderPlacedProjection,
      OrderCancelledProjection orderCancelledProjection,
      OrderQueryService orderQueryService) {
    this.dsl = dsl;
    this.orderPlacedProjection = orderPlacedProjection;
    this.orderCancelledProjection = orderCancelledProjection;
    this.orderQueryService = orderQueryService;
  }

  @BeforeEach
  void clean() {
    dsl.deleteFrom(ORDER_READ_MODEL).execute();
    orderQueryService.evictAll();
  }

  @Test
  @DisplayName("out of order event arrival does not overwrite newer aggregate version state")
  void outOfOrderEventArrivalDoesNotOverwriteNewerAggregateVersionState() {
    long orderId = 88888L;
    Acknowledgment ack = mock(Acknowledgment.class);

    // Step 1: Initial OrderPlaced event arrives with version 1
    String initialPlacedJson =
        """
        {
          "version": 1,
          "eventId": "evt-1",
          "orderId": %d,
          "status": "PLACED",
          "createdAt": "%s",
          "items": [{"sku": "SKU-TEST-1", "quantity": 1}],
          "aggregateVersion": 1
        }
        """
            .formatted(orderId, Instant.now());
    orderPlacedProjection.onOrderPlaced(initialPlacedJson, ack);

    OrderDto order = orderQueryService.getOrder(orderId);
    assertThat(order.status()).isEqualTo("PLACED");

    // Step 2: OrderCancelled event arrives with version 2
    String cancelledJson =
        """
        {
          "version": 1,
          "eventId": "evt-2",
          "orderId": %d,
          "reason": "Customer cancellation",
          "createdAt": "%s",
          "aggregateVersion": 2
        }
        """
            .formatted(orderId, Instant.now());
    orderCancelledProjection.onOrderCancelled(cancelledJson, ack);

    order = orderQueryService.getOrder(orderId);
    assertThat(order.status()).isEqualTo("CANCELLED");

    // Step 3: Delayed replayed / out-of-order OrderPlaced event (version 1) arrives
    String delayedPlacedJson =
        """
        {
          "version": 1,
          "eventId": "evt-1-delayed",
          "orderId": %d,
          "status": "PLACED",
          "createdAt": "%s",
          "items": [{"sku": "SKU-TEST-1", "quantity": 1}],
          "aggregateVersion": 1
        }
        """
            .formatted(orderId, Instant.now());
    orderPlacedProjection.onOrderPlaced(delayedPlacedJson, ack);

    // Verify: The read model MUST NOT regress back to PLACED!
    order = orderQueryService.getOrder(orderId);
    assertThat(order.status()).isEqualTo("CANCELLED");

    Long versionInDb =
        dsl.select(ORDER_READ_MODEL.VERSION)
            .from(ORDER_READ_MODEL)
            .where(ORDER_READ_MODEL.ORDER_ID.eq(orderId))
            .fetchOne(ORDER_READ_MODEL.VERSION);
    assertThat(versionInDb).isEqualTo(2L);
  }

  @Test
  @DisplayName(
      "cancellation arriving before placement preserves cancellation and later enriches items")
  void cancellationArrivingBeforePlacementPreservesCancellationAndLaterEnrichesItems() {
    long orderId = 99999L;
    Acknowledgment ack = mock(Acknowledgment.class);
    Instant cancellationTime = Instant.now();
    Instant placementTime = cancellationTime.minusSeconds(1);

    orderCancelledProjection.onOrderCancelled(
        """
        {
          "version": 1,
          "eventId": "evt-cancel-first",
          "orderId": %d,
          "reason": "Inventory unavailable",
          "createdAt": "%s",
          "aggregateVersion": 2
        }
        """
            .formatted(orderId, cancellationTime),
        ack);

    assertThat(orderQueryService.getOrder(orderId).status()).isEqualTo("CANCELLED");
    assertThat(orderQueryService.getOrder(orderId).items()).isEmpty();

    orderPlacedProjection.onOrderPlaced(
        """
        {
          "version": 1,
          "eventId": "evt-place-late",
          "orderId": %d,
          "status": "PLACED",
          "createdAt": "%s",
          "items": [{"sku": "SKU-TEST-1", "quantity": 1}],
          "aggregateVersion": 1
        }
        """
            .formatted(orderId, placementTime),
        ack);

    OrderDto result = orderQueryService.getOrder(orderId);
    assertThat(result.status()).isEqualTo("CANCELLED");
    assertThat(result.items()).hasSize(1);
    assertThat(result.items().get(0)).extracting("sku").isEqualTo("SKU-TEST-1");
    assertThat(
            dsl.select(ORDER_READ_MODEL.VERSION)
                .from(ORDER_READ_MODEL)
                .where(ORDER_READ_MODEL.ORDER_ID.eq(orderId))
                .fetchOne(ORDER_READ_MODEL.VERSION))
        .isEqualTo(2L);
  }
}
