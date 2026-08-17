package com.hyperscale.commerce.orderquery.application

import com.hyperscale.commerce.orderquery.jooq.order_query.Tables.ORDER_READ_MODEL
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.time.Instant
import java.time.ZoneOffset
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class OrderPlacedProjection(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
    private val orderQueryService: OrderQueryService,
    meterRegistry: MeterRegistry,
) {
  private val logger = LoggerFactory.getLogger(OrderPlacedProjection::class.java)

  private val eventsConsumed: Counter =
      meterRegistry.counter(
          "events_consumed_total",
          "event_type",
          EVENT_TYPE,
          "outcome",
          "processed",
          "consumer",
          CONSUMER_GROUP)

  private val outOfOrderEvents: Counter =
      meterRegistry.counter(
          "events_out_of_order_total", "event_type", EVENT_TYPE, "consumer", CONSUMER_GROUP)

  @KafkaListener(topics = ["\${app.outbox.topic}"], groupId = CONSUMER_GROUP)
  fun onOrderPlaced(message: String, acknowledgment: Acknowledgment) {
    val root = objectMapper.readTree(message)
    val orderId = root.get("orderId").asLong()
    val status = root.get("status").asString()
    val items = root.get("items").toString()
    val aggregateVersion = root.get("aggregateVersion")?.asLong() ?: DEFAULT_VERSION
    val createdAt = Instant.parse(root.get("createdAt").asText()).atOffset(ZoneOffset.UTC)

    val updated =
        dsl.insertInto(
                ORDER_READ_MODEL,
                ORDER_READ_MODEL.ORDER_ID,
                ORDER_READ_MODEL.STATUS,
                ORDER_READ_MODEL.ITEMS,
                ORDER_READ_MODEL.VERSION,
                ORDER_READ_MODEL.CREATED_AT,
            )
            .values(
                DSL.value(orderId),
                DSL.value(status),
                DSL.cast(DSL.value(items), ORDER_READ_MODEL.ITEMS.dataType),
                DSL.value(aggregateVersion),
                DSL.value(createdAt),
            )
            .onConflict(ORDER_READ_MODEL.ORDER_ID)
            .doUpdate()
            .set(ORDER_READ_MODEL.STATUS, status)
            .set(
                ORDER_READ_MODEL.ITEMS, DSL.cast(DSL.value(items), ORDER_READ_MODEL.ITEMS.dataType))
            .set(ORDER_READ_MODEL.VERSION, aggregateVersion)
            .set(ORDER_READ_MODEL.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(ORDER_READ_MODEL.VERSION.le(aggregateVersion))
            .execute()

    if (updated == 0) {
      logger.warn(
          "Ignored out-of-order OrderPlaced event for orderId={} with version={}",
          orderId,
          aggregateVersion,
      )
      outOfOrderEvents.increment()
    } else {
      orderQueryService.evictOrder(orderId)
      eventsConsumed.increment()
    }

    acknowledgment.acknowledge()
  }

  private companion object {
    const val EVENT_TYPE = "OrderPlaced"
    const val CONSUMER_GROUP = "order-query"
    const val DEFAULT_VERSION = 1L
  }
}
