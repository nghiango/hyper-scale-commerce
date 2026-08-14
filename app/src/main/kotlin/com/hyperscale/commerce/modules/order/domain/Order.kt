package com.hyperscale.commerce.modules.order.domain

import java.time.Instant

data class Order(
    val id: Long,
    val status: OrderStatus,
    val items: List<OrderItem>,
    val createdAt: Instant,
)
