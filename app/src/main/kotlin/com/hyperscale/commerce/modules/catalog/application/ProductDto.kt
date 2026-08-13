package com.hyperscale.commerce.modules.catalog.application

data class ProductDto(
    val id: Long,
    val sku: String,
    val name: String,
    val description: String?,
    val price: Int,
    val availability: String,
)
