package com.hyperscale.commerce.contracts;

import java.time.Instant;

public record OrderCancelledEvent(
    Integer version,
    String eventId,
    long orderId,
    String reason,
    Instant createdAt,
    Long aggregateVersion,
    String correlationId,
    String traceId,
    String parentSpanId,
    Boolean sampled) {

  public OrderCancelledEvent {
    if (version == null) {
      version = 1;
    }
    if (aggregateVersion == null) {
      aggregateVersion = 2L;
    }
    if (sampled == null) {
      sampled = true;
    }
  }

  public OrderCancelledEvent(
      Integer version,
      String eventId,
      long orderId,
      String reason,
      Instant createdAt,
      String correlationId,
      String traceId,
      String parentSpanId,
      Boolean sampled) {
    this(
        version,
        eventId,
        orderId,
        reason,
        createdAt,
        null,
        correlationId,
        traceId,
        parentSpanId,
        sampled);
  }
}
