package com.hyperscale.commerce.modules.inventory.infrastructure

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.ReadOnlyProperty
import org.springframework.data.relational.core.mapping.Table

@Table(name = "reservations", schema = "inventory")
data class ReservationEntity(
    @Id val id: Long? = null,
    val orderId: Long,
    val sku: String,
    val quantity: Int,
    val status: String,
    val eventId: String,
    @ReadOnlyProperty val createdAt: Instant? = null,
)
