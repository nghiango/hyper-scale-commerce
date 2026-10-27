package com.hyperscale.commerce.orderquery.application;

import java.time.Instant;
import java.util.List;

public record OrderDto(long id, String status, List<OrderItemDto> items, Instant createdAt) {}
