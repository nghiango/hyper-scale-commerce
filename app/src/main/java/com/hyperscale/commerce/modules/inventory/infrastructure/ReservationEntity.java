package com.hyperscale.commerce.modules.inventory.infrastructure;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

@Table(name = "reservations", schema = "inventory")
public record ReservationEntity(
    @Id Long id,
    long orderId,
    String sku,
    int quantity,
    String status,
    String eventId,
    @ReadOnlyProperty Instant createdAt) {}
