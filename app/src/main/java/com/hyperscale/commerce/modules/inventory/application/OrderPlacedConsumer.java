package com.hyperscale.commerce.modules.inventory.application;

import com.hyperscale.commerce.config.observability.CorrelationIdFilter;
import com.hyperscale.commerce.contracts.InventoryReservationFailedEvent;
import com.hyperscale.commerce.modules.inventory.domain.ReservationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderPlacedConsumer {
  private static final int MAX_ALLOWABLE_STOCK = 9999;
  private static final String EVENT_TYPE = "OrderPlaced";
  private static final String CONSUMER_GROUP = "inventory";
  private static final String FAILED_TOPIC = "inventory-reservation-failed";

  private final ReservationRepository reservationRepository;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final Tracer tracer;
  private final Counter eventsConsumedProcessed;
  private final Counter eventsConsumedDuplicate;
  private final Counter reservationsFailed;

  public OrderPlacedConsumer(
      ReservationRepository reservationRepository,
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      Tracer tracer,
      MeterRegistry meterRegistry) {
    this.reservationRepository = reservationRepository;
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.tracer = tracer;
    this.eventsConsumedProcessed =
        meterRegistry.counter(
            "events_consumed_total",
            "event_type",
            EVENT_TYPE,
            "outcome",
            "processed",
            "consumer",
            CONSUMER_GROUP);
    this.eventsConsumedDuplicate =
        meterRegistry.counter(
            "events_consumed_total",
            "event_type",
            EVENT_TYPE,
            "outcome",
            "duplicate",
            "consumer",
            CONSUMER_GROUP);
    this.reservationsFailed =
        meterRegistry.counter("saga_reservations_failed_total", "reason", "out_of_stock");
  }

  @KafkaListener(topics = "${app.outbox.topic}", groupId = CONSUMER_GROUP)
  public void onOrderPlaced(String message, Acknowledgment acknowledgment) {
    JsonNode root = objectMapper.readTree(message);
    String eventId = root.get("eventId").asString();
    long orderId = root.get("orderId").asLong();

    boolean hasOutOfStockItem = false;
    for (JsonNode item : root.get("items")) {
      String sku = item.get("sku").asString();
      int quantity = item.get("quantity").asInt();
      if (sku.toLowerCase(Locale.ROOT).contains("oos")
          || sku.toLowerCase(Locale.ROOT).contains("out-of-stock")
          || quantity > MAX_ALLOWABLE_STOCK) {
        hasOutOfStockItem = true;
        break;
      }
    }

    if (hasOutOfStockItem) {
      publishReservationFailure(orderId);
      reservationsFailed.increment();
      acknowledgment.acknowledge();
      return;
    }

    boolean insertedAny = false;
    for (JsonNode item : root.get("items")) {
      boolean inserted =
          reservationRepository.recordIfAbsent(
              orderId, item.get("sku").asString(), item.get("quantity").asInt(), eventId);
      insertedAny = insertedAny || inserted;
    }
    if (insertedAny) {
      eventsConsumedProcessed.increment();
    } else {
      eventsConsumedDuplicate.increment();
    }
    acknowledgment.acknowledge();
  }

  private void publishReservationFailure(long orderId) {
    Span span = tracer.currentSpan();
    TraceContext traceContext = span != null ? span.context() : null;
    InventoryReservationFailedEvent failedEvent =
        new InventoryReservationFailedEvent(
            1,
            UUID.randomUUID().toString(),
            orderId,
            "Item is out of stock",
            Instant.now(),
            MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY),
            traceContext != null ? traceContext.traceId() : null,
            traceContext != null ? traceContext.spanId() : null,
            traceContext != null ? traceContext.sampled() : null);
    String payload = objectMapper.writeValueAsString(failedEvent);
    kafkaTemplate.send(FAILED_TOPIC, Long.toString(orderId), payload);
  }
}
