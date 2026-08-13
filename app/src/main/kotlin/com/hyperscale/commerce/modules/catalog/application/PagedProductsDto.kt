package com.hyperscale.commerce.modules.catalog.application

data class PagedProductsDto(
    val total: Long,
    val items: List<ProductDto>,
)
