package com.hyperscale.commerce.modules.order.infrastructure

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.ReadOnlyProperty
import org.springframework.data.relational.core.mapping.MappedCollection
import org.springframework.data.relational.core.mapping.Table

@Table(name = "orders", schema = "order")
data class OrderEntity(
    @Id val id: Long? = null,
    val status: String,
    @ReadOnlyProperty val createdAt: Instant? = null,
    @MappedCollection(idColumn = "order_id", keyColumn = "orders_key")
    val items: List<OrderItemEntity> = emptyList(),
)
