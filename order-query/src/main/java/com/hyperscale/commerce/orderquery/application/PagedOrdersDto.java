package com.hyperscale.commerce.orderquery.application;

import java.util.List;

public record PagedOrdersDto(long total, List<OrderDto> items) {}
