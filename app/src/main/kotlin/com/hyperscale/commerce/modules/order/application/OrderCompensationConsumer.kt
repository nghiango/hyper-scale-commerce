package com.hyperscale.commerce.modules.order.application

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class OrderCompensationConsumer(
    private val orderService: OrderService,
    private val objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry,
) {

  private val logger = LoggerFactory.getLogger(OrderCompensationConsumer::class.java)

  private val compensationsProcessed: Counter =
      meterRegistry.counter(
          "saga_compensations_total",
          "event_type",
          EVENT_TYPE,
          "outcome",
          "processed",
      )

  @KafkaListener(
      topics = ["inventory-reservation-failed"],
      groupId = CONSUMER_GROUP,
  )
  fun onInventoryReservationFailed(message: String, acknowledgment: Acknowledgment) {
    val root = objectMapper.readTree(message)
    val orderId = root.get("orderId").asLong()
    val reason = root.path("reason").asText("Inventory reservation failed")
    logger.info("Received inventory reservation failure for orderId={}, reason={}", orderId, reason)

    val cancelled = orderService.cancelOrder(orderId, reason)
    if (cancelled) {
      compensationsProcessed.increment()
    }
    acknowledgment.acknowledge()
  }

  private companion object {
    const val EVENT_TYPE = "InventoryReservationFailed"
    const val CONSUMER_GROUP = "order-compensation"
  }
}
