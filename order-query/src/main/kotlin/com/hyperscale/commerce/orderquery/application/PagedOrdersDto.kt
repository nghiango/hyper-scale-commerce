package com.hyperscale.commerce.orderquery.application

data class PagedOrdersDto(
    val total: Long,
    val items: List<OrderDto>,
)
