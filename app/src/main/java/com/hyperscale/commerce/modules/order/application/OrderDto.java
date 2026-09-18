package com.hyperscale.commerce.modules.order.application;

import java.time.Instant;
import java.util.List;

public record OrderDto(long id, String status, List<OrderItemDto> items, Instant createdAt) {}
