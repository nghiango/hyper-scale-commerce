package com.hyperscale.commerce.modules.inventory.domain;

public interface ReservationRepository {

  boolean recordIfAbsent(long orderId, String sku, int quantity, String eventId);

  long countByEventId(String eventId);
}
