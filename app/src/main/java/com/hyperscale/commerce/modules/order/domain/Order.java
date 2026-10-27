package com.hyperscale.commerce.modules.order.domain;

import java.time.Instant;
import java.util.List;

public record Order(long id, OrderStatus status, List<OrderItem> items, Instant createdAt) {}
