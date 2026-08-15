package com.hyperscale.commerce.contracts

import java.time.Instant

data class OrderPlacedEvent(
    val version: Int,
    val eventId: String,
    val orderId: Long,
    val status: String,
    val createdAt: Instant,
    val items: List<OrderPlacedItem>,
    val correlationId: String? = null,
    val traceId: String? = null,
    val parentSpanId: String? = null,
    val sampled: Boolean? = true,
)

data class OrderPlacedItem(val sku: String, val quantity: Int)
