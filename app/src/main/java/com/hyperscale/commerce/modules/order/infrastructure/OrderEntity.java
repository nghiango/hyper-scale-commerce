package com.hyperscale.commerce.modules.order.infrastructure;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

@Table(name = "orders", schema = "order")
public record OrderEntity(
    @Id Long id,
    String status,
    @ReadOnlyProperty Instant createdAt,
    @MappedCollection(idColumn = "order_id", keyColumn = "orders_key")
        List<OrderItemEntity> items) {}
