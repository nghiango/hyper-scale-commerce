package com.hyperscale.commerce.modules.order.application

data class PagedOrdersDto(
    val total: Long,
    val items: List<OrderDto>,
)
