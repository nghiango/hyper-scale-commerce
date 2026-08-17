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
    val event = parse(message)
    val updated = upsert(event)
    val enrichedCancellationTombstone = if (updated == 0) enrichCancellationTombstone(event) else 0

    recordOutcome(event, updated, enrichedCancellationTombstone)
    acknowledgment.acknowledge()
  }

  private fun parse(message: String): PlacedEvent {
    val root = objectMapper.readTree(message)
    return PlacedEvent(
        orderId = root.get("orderId").asLong(),
        status = root.get("status").asString(),
        items = root.get("items").toString(),
        aggregateVersion = root.get("aggregateVersion")?.asLong() ?: DEFAULT_VERSION,
        createdAt = Instant.parse(root.get("createdAt").asString()).atOffset(ZoneOffset.UTC),
    )
  }

  private fun upsert(event: PlacedEvent): Int =
      dsl.insertInto(
              ORDER_READ_MODEL,
              ORDER_READ_MODEL.ORDER_ID,
              ORDER_READ_MODEL.STATUS,
              ORDER_READ_MODEL.ITEMS,
              ORDER_READ_MODEL.VERSION,
              ORDER_READ_MODEL.CREATED_AT,
          )
          .values(
              DSL.value(event.orderId),
              DSL.value(event.status),
              DSL.cast(DSL.value(event.items), ORDER_READ_MODEL.ITEMS.dataType),
              DSL.value(event.aggregateVersion),
              DSL.value(event.createdAt),
          )
          .onConflict(ORDER_READ_MODEL.ORDER_ID)
          .doUpdate()
          .set(ORDER_READ_MODEL.STATUS, event.status)
          .set(
              ORDER_READ_MODEL.ITEMS,
              DSL.cast(DSL.value(event.items), ORDER_READ_MODEL.ITEMS.dataType))
          .set(ORDER_READ_MODEL.VERSION, event.aggregateVersion)
          .set(ORDER_READ_MODEL.UPDATED_AT, DSL.currentOffsetDateTime())
          .where(ORDER_READ_MODEL.VERSION.le(event.aggregateVersion))
          .execute()

  private fun enrichCancellationTombstone(event: PlacedEvent): Int =
      dsl.update(ORDER_READ_MODEL)
          .set(
              ORDER_READ_MODEL.ITEMS,
              DSL.cast(DSL.value(event.items), ORDER_READ_MODEL.ITEMS.dataType),
          )
          .set(ORDER_READ_MODEL.CREATED_AT, event.createdAt)
          .set(ORDER_READ_MODEL.UPDATED_AT, DSL.currentOffsetDateTime())
          .where(
              ORDER_READ_MODEL.ORDER_ID.eq(event.orderId),
              ORDER_READ_MODEL.STATUS.eq("CANCELLED"),
              ORDER_READ_MODEL.VERSION.gt(event.aggregateVersion),
              DSL.condition("{0} = '[]'::jsonb", ORDER_READ_MODEL.ITEMS),
          )
          .execute()

  private fun recordOutcome(event: PlacedEvent, updated: Int, enriched: Int) {
    if (updated == 0 && enriched == 0) {
      logger.warn(
          "Ignored out-of-order OrderPlaced event for orderId={} with version={}",
          event.orderId,
          event.aggregateVersion,
      )
      outOfOrderEvents.increment()
    } else {
      if (enriched > 0) {
        logger.info(
            "Enriched cancellation-first projection for orderId={} without regressing version={}",
            event.orderId,
            event.aggregateVersion,
        )
        outOfOrderEvents.increment()
      }
      orderQueryService.evictOrder(event.orderId)
      eventsConsumed.increment()
    }
  }

  private data class PlacedEvent(
      val orderId: Long,
      val status: String,
      val items: String,
      val aggregateVersion: Long,
      val createdAt: java.time.OffsetDateTime,
  )

  private companion object {
    const val EVENT_TYPE = "OrderPlaced"
    const val CONSUMER_GROUP = "order-query"
    const val DEFAULT_VERSION = 1L
  }
}
