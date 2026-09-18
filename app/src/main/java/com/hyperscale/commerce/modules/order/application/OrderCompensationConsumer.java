package com.hyperscale.commerce.modules.order.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderCompensationConsumer {
  private static final String EVENT_TYPE = "InventoryReservationFailed";
  private static final String CONSUMER_GROUP = "order-compensation";

  private final OrderService orderService;
  private final ObjectMapper objectMapper;
  private final Logger logger = LoggerFactory.getLogger(OrderCompensationConsumer.class);
  private final Counter compensationsProcessed;

  public OrderCompensationConsumer(
      OrderService orderService, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
    this.orderService = orderService;
    this.objectMapper = objectMapper;
    this.compensationsProcessed =
        meterRegistry.counter(
            "saga_compensations_total", "event_type", EVENT_TYPE, "outcome", "processed");
  }

  @KafkaListener(topics = "inventory-reservation-failed", groupId = CONSUMER_GROUP)
  public void onInventoryReservationFailed(String message, Acknowledgment acknowledgment) {
    JsonNode root = objectMapper.readTree(message);
    long orderId = root.get("orderId").asLong();
    JsonNode reasonNode = root.get("reason");
    String reason =
        reasonNode != null && !reasonNode.isNull()
            ? reasonNode.asString()
            : "Inventory reservation failed";
    logger.info(
        "Received inventory reservation failure for orderId={}, reason={}", orderId, reason);

    boolean cancelled = orderService.cancelOrder(orderId, reason);
    if (cancelled) {
      compensationsProcessed.increment();
    }
    acknowledgment.acknowledge();
  }
}
