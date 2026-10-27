package com.hyperscale.commerce.contracts;

import java.time.Instant;
import java.util.List;

public record OrderPlacedEvent(
    Integer version,
    String eventId,
    long orderId,
    String status,
    Instant createdAt,
    List<OrderPlacedItem> items,
    Long aggregateVersion,
    String correlationId,
    String traceId,
    String parentSpanId,
    Boolean sampled) {

  public OrderPlacedEvent {
    if (version == null) {
      version = 1;
    }
    if (aggregateVersion == null) {
      aggregateVersion = 1L;
    }
    if (sampled == null) {
      sampled = true;
    }
  }

  public OrderPlacedEvent(
      Integer version,
      String eventId,
      long orderId,
      String status,
      Instant createdAt,
      List<OrderPlacedItem> items,
      String correlationId,
      String traceId,
      String parentSpanId,
      Boolean sampled) {
    this(
        version,
        eventId,
        orderId,
        status,
        createdAt,
        items,
        null,
        correlationId,
        traceId,
        parentSpanId,
        sampled);
  }
}
