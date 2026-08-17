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
    val savedId = checkNotNull(saved.id) { "Order ID must be generated after save" }
    val reloaded = orderJdbcRepository.findById(savedId).orElseThrow()
    return reloaded.toOrder()
  }

  override fun findById(id: Long): Order? =
      orderJdbcRepository.findById(id).map { it.toOrder() }.orElse(null)

  override fun updateStatus(id: Long, status: OrderStatus): Boolean {
    val rows = orderJdbcRepository.updateStatus(id, status.name)
    return rows > 0
  }

  private fun OrderEntity.toOrder(): Order =
      Order(
          id = checkNotNull(id) { "Order entity ID must not be null" },
          status = OrderStatus.valueOf(status),
          items = items.map { OrderItem(sku = it.sku, quantity = it.quantity) },
          createdAt = checkNotNull(createdAt) { "Order entity createdAt must not be null" },
      )
}
