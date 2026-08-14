package com.hyperscale.commerce.modules.order.infrastructure

import com.hyperscale.commerce.modules.order.domain.Order
import com.hyperscale.commerce.modules.order.domain.OrderItem
import com.hyperscale.commerce.modules.order.domain.OrderRepository
import com.hyperscale.commerce.modules.order.domain.OrderStatus
import org.springframework.stereotype.Repository

@Repository
class SpringDataJdbcOrderRepository(
    private val orderJdbcRepository: OrderJdbcRepository,
) : OrderRepository {

  override fun save(items: List<OrderItem>): Order {
    val entity =
        OrderEntity(
            status = OrderStatus.PLACED.name,
            items = items.map { OrderItemEntity(sku = it.sku, quantity = it.quantity) },
        )
    val saved = orderJdbcRepository.save(entity)
    val reloaded = orderJdbcRepository.findById(saved.id!!).orElseThrow()
    return reloaded.toOrder()
  }

  override fun findById(id: Long): Order? =
      orderJdbcRepository.findById(id).map { it.toOrder() }.orElse(null)

  private fun OrderEntity.toOrder(): Order =
      Order(
          id = id!!,
          status = OrderStatus.valueOf(status),
          items = items.map { OrderItem(sku = it.sku, quantity = it.quantity) },
          createdAt = createdAt!!,
      )
}
