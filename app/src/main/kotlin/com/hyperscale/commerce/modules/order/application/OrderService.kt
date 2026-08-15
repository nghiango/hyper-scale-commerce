package com.hyperscale.commerce.modules.order.application

import com.hyperscale.commerce.config.observability.CorrelationIdFilter
import com.hyperscale.commerce.contracts.OrderPlacedEvent
import com.hyperscale.commerce.contracts.OrderPlacedItem
import com.hyperscale.commerce.modules.order.domain.Order
import com.hyperscale.commerce.modules.order.domain.OrderItem
import com.hyperscale.commerce.modules.order.domain.OrderRepository
import com.hyperscale.commerce.modules.shared.outbox.OutboxRepository
import io.micrometer.tracing.Tracer
import java.util.UUID
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val outboxRepository: OutboxRepository,
    private val objectMapper: ObjectMapper,
    private val tracer: Tracer,
) {

  private val logger = LoggerFactory.getLogger(OrderService::class.java)

  @Transactional
  fun createOrder(items: List<OrderItem>): OrderDto {
    val order = orderRepository.save(items)
    logger.info(
        "OrderService creating order with mdcCorrelationId={} tracerCurrentTraceId={}",
        MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY),
        tracer.currentSpan()?.context()?.traceId(),
    )
    val span = tracer.currentSpan()
    val payload =
        objectMapper.writeValueAsString(
            OrderPlacedEvent(
                version = EVENT_VERSION,
                eventId = UUID.randomUUID().toString(),
                orderId = order.id,
                status = order.status.name,
                createdAt = order.createdAt,
                items = order.items.map { OrderPlacedItem(it.sku, it.quantity) },
                correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY),
                traceId = span?.context()?.traceId(),
                parentSpanId = span?.context()?.spanId(),
                sampled = span?.context()?.sampled(),
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
