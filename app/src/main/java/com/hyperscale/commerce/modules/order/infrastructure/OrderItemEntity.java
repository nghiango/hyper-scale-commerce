package com.hyperscale.commerce.modules.order.infrastructure;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table(name = "order_items", schema = "order")
public record OrderItemEntity(@Id Long id, String sku, int quantity) {}
