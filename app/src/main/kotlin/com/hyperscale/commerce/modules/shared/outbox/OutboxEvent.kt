package com.hyperscale.commerce.modules.shared.outbox

import java.time.Instant

data class OutboxEvent(
    val id: Long,
    val aggregateId: String,
    val eventType: String,
    val payload: String,
    val createdAt: Instant,
    val publishedAt: Instant?,
)
