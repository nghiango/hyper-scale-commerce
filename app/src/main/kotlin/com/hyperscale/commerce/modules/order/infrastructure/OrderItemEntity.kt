package com.hyperscale.commerce.modules.order.infrastructure

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table(name = "order_items", schema = "order")
data class OrderItemEntity(
    @Id val id: Long? = null,
    val sku: String,
    val quantity: Int,
)
