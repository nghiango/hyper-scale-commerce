package com.hyperscale.commerce.contracts;

import java.time.Instant;

public record InventoryReservationFailedEvent(
    Integer version,
    String eventId,
    long orderId,
    String reason,
    Instant createdAt,
    String correlationId,
    String traceId,
    String parentSpanId,
    Boolean sampled) {

  public InventoryReservationFailedEvent {
    if (version == null) {
      version = 1;
    }
    if (sampled == null) {
      sampled = true;
    }
  }
}
