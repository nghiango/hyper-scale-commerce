package com.hyperscale.commerce.orderquery.application;

import static com.hyperscale.commerce.orderquery.jooq.order_query.Tables.ORDER_READ_MODEL;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderCancelledProjection {
  private static final Logger logger = LoggerFactory.getLogger(OrderCancelledProjection.class);

  private static final String EVENT_TYPE = "OrderCancelled";
  private static final String CONSUMER_GROUP = "order-query";
  private static final long DEFAULT_VERSION = 2L;
  private static final String EMPTY_ITEMS = "[]";

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;
  private final OrderQueryService orderQueryService;
  private final Counter eventsConsumed;
  private final Counter outOfOrderEvents;

  public OrderCancelledProjection(
      DSLContext dsl,
      ObjectMapper objectMapper,
      OrderQueryService orderQueryService,
      MeterRegistry meterRegistry) {
    this.dsl = dsl;
    this.objectMapper = objectMapper;
    this.orderQueryService = orderQueryService;
    this.eventsConsumed =
        meterRegistry.counter(
            "events_consumed_total",
            "event_type",
            EVENT_TYPE,
            "outcome",
            "processed",
            "consumer",
            CONSUMER_GROUP);
    this.outOfOrderEvents =
        meterRegistry.counter(
            "events_out_of_order_total", "event_type", EVENT_TYPE, "consumer", CONSUMER_GROUP);
  }

  @KafkaListener(
      topics = {"order-cancelled"},
      groupId = CONSUMER_GROUP)
  public void onOrderCancelled(String message, Acknowledgment acknowledgment) {
    JsonNode root = objectMapper.readTree(message);
    long orderId = root.get("orderId").asLong();
    JsonNode aggregateVersionNode = root.get("aggregateVersion");
    long aggregateVersion =
        aggregateVersionNode != null ? aggregateVersionNode.asLong() : DEFAULT_VERSION;
    OffsetDateTime createdAt =
        Instant.parse(root.get("createdAt").asString()).atOffset(ZoneOffset.UTC);
    logger.info(
        "Projecting order cancellation for orderId={} version={}", orderId, aggregateVersion);

    int updated =
        dsl.insertInto(
                ORDER_READ_MODEL,
                ORDER_READ_MODEL.ORDER_ID,
                ORDER_READ_MODEL.STATUS,
                ORDER_READ_MODEL.ITEMS,
                ORDER_READ_MODEL.VERSION,
                ORDER_READ_MODEL.CREATED_AT)
            .values(
                DSL.value(orderId),
                DSL.value("CANCELLED"),
                DSL.cast(DSL.value(EMPTY_ITEMS), ORDER_READ_MODEL.ITEMS.getDataType()),
                DSL.value(aggregateVersion),
                DSL.value(createdAt))
            .onConflict(ORDER_READ_MODEL.ORDER_ID)
            .doUpdate()
            .set(ORDER_READ_MODEL.STATUS, "CANCELLED")
            .set(ORDER_READ_MODEL.VERSION, aggregateVersion)
            .set(ORDER_READ_MODEL.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(ORDER_READ_MODEL.VERSION.le(aggregateVersion))
            .execute();

    if (updated == 0) {
      logger.warn(
          "Ignored out-of-order OrderCancelled event for orderId={} with version={}",
          orderId,
          aggregateVersion);
      outOfOrderEvents.increment();
    } else {
      orderQueryService.evictOrder(orderId);
      eventsConsumed.increment();
    }

    acknowledgment.acknowledge();
  }
}
