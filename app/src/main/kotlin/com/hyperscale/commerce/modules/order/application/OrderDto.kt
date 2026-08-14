package com.hyperscale.commerce.modules.order.application

import java.time.Instant

data class OrderDto(
    val id: Long,
    val status: String,
    val items: List<OrderItemDto>,
    val createdAt: Instant,
)

data class OrderItemDto(val sku: String, val quantity: Int)
