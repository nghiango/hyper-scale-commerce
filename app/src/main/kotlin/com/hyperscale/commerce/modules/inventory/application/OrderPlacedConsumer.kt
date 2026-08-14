package com.hyperscale.commerce.modules.inventory.application

import com.hyperscale.commerce.modules.inventory.domain.ReservationRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class OrderPlacedConsumer(
    private val reservationRepository: ReservationRepository,
    private val objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry,
) {
  private val eventsConsumedProcessed: Counter =
      meterRegistry.counter(
          "events_consumed_total",
          "event_type",
          EVENT_TYPE,
          "outcome",
          "processed",
          "consumer",
          CONSUMER_GROUP)
  private val eventsConsumedDuplicate: Counter =
      meterRegistry.counter(
          "events_consumed_total",
          "event_type",
          EVENT_TYPE,
          "outcome",
          "duplicate",
          "consumer",
          CONSUMER_GROUP)

  @KafkaListener(topics = ["\${app.outbox.topic}"], groupId = CONSUMER_GROUP)
  fun onOrderPlaced(message: String, acknowledgment: Acknowledgment) {
    val root = objectMapper.readTree(message)
    val eventId = root.get("eventId").asString()
    val orderId = root.get("orderId").asLong()
    var insertedAny = false
    root.get("items").forEach { item ->
      val inserted =
          reservationRepository.recordIfAbsent(
              orderId = orderId,
              sku = item.get("sku").asString(),
              quantity = item.get("quantity").asInt(),
              eventId = eventId,
          )
      insertedAny = insertedAny || inserted
    }
    if (insertedAny) {
      eventsConsumedProcessed.increment()
    } else {
      eventsConsumedDuplicate.increment()
    }
    acknowledgment.acknowledge()
  }

  private companion object {
    const val EVENT_TYPE = "OrderPlaced"
    const val CONSUMER_GROUP = "inventory"
  }
}
