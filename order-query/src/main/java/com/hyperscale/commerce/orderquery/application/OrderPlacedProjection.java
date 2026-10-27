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
public class OrderPlacedProjection {
  private static final Logger logger = LoggerFactory.getLogger(OrderPlacedProjection.class);

  private static final String EVENT_TYPE = "OrderPlaced";
  private static final String CONSUMER_GROUP = "order-query";
  private static final long DEFAULT_VERSION = 1L;

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;
  private final OrderQueryService orderQueryService;
  private final Counter eventsConsumed;
  private final Counter outOfOrderEvents;

  public OrderPlacedProjection(
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
      topics = {"${app.outbox.topic}"},
      groupId = CONSUMER_GROUP)
  public void onOrderPlaced(String message, Acknowledgment acknowledgment) {
    PlacedEvent event = parse(message);
    int updated = upsert(event);
    int enrichedCancellationTombstone = updated == 0 ? enrichCancellationTombstone(event) : 0;

    recordOutcome(event, updated, enrichedCancellationTombstone);
    acknowledgment.acknowledge();
  }

  private PlacedEvent parse(String message) {
    JsonNode root = objectMapper.readTree(message);
    JsonNode aggregateVersionNode = root.get("aggregateVersion");
    return new PlacedEvent(
        root.get("orderId").asLong(),
        root.get("status").asString(),
        root.get("items").toString(),
        aggregateVersionNode != null ? aggregateVersionNode.asLong() : DEFAULT_VERSION,
        Instant.parse(root.get("createdAt").asString()).atOffset(ZoneOffset.UTC));
  }

  private int upsert(PlacedEvent event) {
    return dsl.insertInto(
            ORDER_READ_MODEL,
            ORDER_READ_MODEL.ORDER_ID,
            ORDER_READ_MODEL.STATUS,
            ORDER_READ_MODEL.ITEMS,
            ORDER_READ_MODEL.VERSION,
            ORDER_READ_MODEL.CREATED_AT)
        .values(
            DSL.value(event.orderId()),
            DSL.value(event.status()),
            DSL.cast(DSL.value(event.items()), ORDER_READ_MODEL.ITEMS.getDataType()),
            DSL.value(event.aggregateVersion()),
            DSL.value(event.createdAt()))
        .onConflict(ORDER_READ_MODEL.ORDER_ID)
        .doUpdate()
        .set(ORDER_READ_MODEL.STATUS, event.status())
        .set(
            ORDER_READ_MODEL.ITEMS,
            DSL.cast(DSL.value(event.items()), ORDER_READ_MODEL.ITEMS.getDataType()))
        .set(ORDER_READ_MODEL.VERSION, event.aggregateVersion())
        .set(ORDER_READ_MODEL.UPDATED_AT, DSL.currentOffsetDateTime())
        .where(ORDER_READ_MODEL.VERSION.le(event.aggregateVersion()))
        .execute();
  }

  private int enrichCancellationTombstone(PlacedEvent event) {
    return dsl.update(ORDER_READ_MODEL)
        .set(
            ORDER_READ_MODEL.ITEMS,
            DSL.cast(DSL.value(event.items()), ORDER_READ_MODEL.ITEMS.getDataType()))
        .set(ORDER_READ_MODEL.CREATED_AT, event.createdAt())
        .set(ORDER_READ_MODEL.UPDATED_AT, DSL.currentOffsetDateTime())
        .where(
            ORDER_READ_MODEL.ORDER_ID.eq(event.orderId()),
            ORDER_READ_MODEL.STATUS.eq("CANCELLED"),
            ORDER_READ_MODEL.VERSION.gt(event.aggregateVersion()),
            DSL.condition("{0} = '[]'::jsonb", ORDER_READ_MODEL.ITEMS))
        .execute();
  }

  private void recordOutcome(PlacedEvent event, int updated, int enriched) {
    if (updated == 0 && enriched == 0) {
      logger.warn(
          "Ignored out-of-order OrderPlaced event for orderId={} with version={}",
          event.orderId(),
          event.aggregateVersion());
      outOfOrderEvents.increment();
    } else {
      if (enriched > 0) {
        logger.info(
            "Enriched cancellation-first projection for orderId={} without regressing version={}",
            event.orderId(),
            event.aggregateVersion());
        outOfOrderEvents.increment();
      }
      orderQueryService.evictOrder(event.orderId());
      eventsConsumed.increment();
    }
  }

  private record PlacedEvent(
      long orderId, String status, String items, long aggregateVersion, OffsetDateTime createdAt) {}
}
