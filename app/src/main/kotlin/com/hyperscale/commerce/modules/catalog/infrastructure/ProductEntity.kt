package com.hyperscale.commerce.modules.catalog.infrastructure

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("catalog.products")
data class ProductEntity(
    @Id val id: Long,
    val sku: String,
    val name: String,
    val description: String?,
    val price: Int,
    val availability: String,
)
