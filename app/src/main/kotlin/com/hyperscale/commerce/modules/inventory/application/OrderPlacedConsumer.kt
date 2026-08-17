package com.hyperscale.commerce.modules.inventory.application

import com.hyperscale.commerce.config.observability.CorrelationIdFilter
import com.hyperscale.commerce.contracts.InventoryReservationFailedEvent
import com.hyperscale.commerce.modules.inventory.domain.ReservationRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.tracing.Tracer
import java.time.Instant
import java.util.UUID
import org.slf4j.MDC
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

private const val MAX_ALLOWABLE_STOCK = 9999

@Service
class OrderPlacedConsumer(
    private val reservationRepository: ReservationRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val tracer: Tracer,
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
  private val reservationsFailed: Counter =
      meterRegistry.counter(
          "saga_reservations_failed_total",
          "reason",
          "out_of_stock",
      )

  @KafkaListener(topics = ["\${app.outbox.topic}"], groupId = CONSUMER_GROUP)
  fun onOrderPlaced(message: String, acknowledgment: Acknowledgment) {
    val root = objectMapper.readTree(message)
    val eventId = root.get("eventId").asString()
    val orderId = root.get("orderId").asLong()

    val hasOutOfStockItem =
        root.get("items").any { item ->
          val sku = item.get("sku").asString()
          val quantity = item.get("quantity").asInt()
          sku.contains("OOS", ignoreCase = true) ||
              sku.contains("OUT-OF-STOCK", ignoreCase = true) ||
              quantity > MAX_ALLOWABLE_STOCK
        }

    if (hasOutOfStockItem) {
      publishReservationFailure(orderId)
      reservationsFailed.increment()
      acknowledgment.acknowledge()
      return
    }

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

  private fun publishReservationFailure(orderId: Long) {
    val span = tracer.currentSpan()
    val failedEvent =
        InventoryReservationFailedEvent(
            version = 1,
            eventId = UUID.randomUUID().toString(),
            orderId = orderId,
            reason = "Item is out of stock",
            createdAt = Instant.now(),
            correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY),
            traceId = span?.context()?.traceId(),
            parentSpanId = span?.context()?.spanId(),
            sampled = span?.context()?.sampled(),
        )
    val payload = objectMapper.writeValueAsString(failedEvent)
    kafkaTemplate.send(FAILED_TOPIC, orderId.toString(), payload)
  }

  private companion object {
    const val EVENT_TYPE = "OrderPlaced"
    const val CONSUMER_GROUP = "inventory"
    const val FAILED_TOPIC = "inventory-reservation-failed"
  }
}
