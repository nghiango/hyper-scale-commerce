package com.hyperscale.commerce.contracts

import java.time.Instant

data class InventoryReservationFailedEvent(
    val version: Int = 1,
    val eventId: String,
    val orderId: Long,
    val reason: String,
    val createdAt: Instant,
    val correlationId: String? = null,
    val traceId: String? = null,
    val parentSpanId: String? = null,
    val sampled: Boolean? = true,
)
