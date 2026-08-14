package com.hyperscale.commerce.modules.order.domain

interface OrderRepository {

  fun save(items: List<OrderItem>): Order

  fun findById(id: Long): Order?
}
