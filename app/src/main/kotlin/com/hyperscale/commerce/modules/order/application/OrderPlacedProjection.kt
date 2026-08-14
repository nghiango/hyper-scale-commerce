package com.hyperscale.commerce.modules.order.application

import com.hyperscale.commerce.jooq.order.Tables.ORDER_READ_MODEL
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.time.Instant
import java.time.ZoneOffset
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class OrderPlacedProjection(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry,
) {
  private val eventsConsumed: Counter =
      meterRegistry.counter(
          "events_consumed_total", "event_type", EVENT_TYPE, "consumer", CONSUMER_GROUP)

  @KafkaListener(topics = ["\${app.outbox.topic}"], groupId = CONSUMER_GROUP)
  fun onOrderPlaced(message: String, acknowledgment: Acknowledgment) {
    val root = objectMapper.readTree(message)
    val orderId = root.get("orderId").asLong()
    val status = root.get("status").asString()
    val items = root.get("items").toString()
    val createdAt = Instant.parse(root.get("createdAt").asText()).atOffset(ZoneOffset.UTC)
    dsl.insertInto(
            ORDER_READ_MODEL,
            ORDER_READ_MODEL.ORDER_ID,
            ORDER_READ_MODEL.STATUS,
            ORDER_READ_MODEL.ITEMS,
            ORDER_READ_MODEL.CREATED_AT,
        )
        .values(
            DSL.value(orderId),
            DSL.value(status),
            DSL.cast(DSL.value(items), ORDER_READ_MODEL.ITEMS.dataType),
            DSL.value(createdAt),
        )
        .onConflict(ORDER_READ_MODEL.ORDER_ID)
        .doUpdate()
        .set(ORDER_READ_MODEL.STATUS, status)
        .set(ORDER_READ_MODEL.ITEMS, DSL.cast(DSL.value(items), ORDER_READ_MODEL.ITEMS.dataType))
        .set(ORDER_READ_MODEL.UPDATED_AT, DSL.currentOffsetDateTime())
        .execute()
    eventsConsumed.increment()
    acknowledgment.acknowledge()
  }

  private companion object {
    const val EVENT_TYPE = "OrderPlaced"
    const val CONSUMER_GROUP = "order-query"
  }
}
