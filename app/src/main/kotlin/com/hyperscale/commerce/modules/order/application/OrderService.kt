package com.hyperscale.commerce.modules.order.application

import com.hyperscale.commerce.modules.order.domain.Order
import com.hyperscale.commerce.modules.order.domain.OrderItem
import com.hyperscale.commerce.modules.order.domain.OrderRepository
import com.hyperscale.commerce.modules.shared.outbox.OutboxRepository
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val outboxRepository: OutboxRepository,
    private val objectMapper: ObjectMapper,
) {

  @Transactional
  fun createOrder(items: List<OrderItem>): OrderDto {
    val order = orderRepository.save(items)
    val payload =
        objectMapper.writeValueAsString(
            OrderPlacedEvent(
                version = EVENT_VERSION,
                eventId = UUID.randomUUID().toString(),
                orderId = order.id,
                status = order.status.name,
                createdAt = order.createdAt,
                items = order.items.map { OrderPlacedItem(it.sku, it.quantity) },
            ))
    outboxRepository.insert(order.id.toString(), ORDER_PLACED_EVENT_TYPE, payload)
    return order.toDto()
  }

  private fun Order.toDto(): OrderDto =
      OrderDto(
          id = id,
          status = status.name,
          items = items.map { OrderItemDto(it.sku, it.quantity) },
          createdAt = createdAt,
      )

  companion object {
    private const val EVENT_VERSION = 1
    private const val ORDER_PLACED_EVENT_TYPE = "OrderPlaced"
  }
}
