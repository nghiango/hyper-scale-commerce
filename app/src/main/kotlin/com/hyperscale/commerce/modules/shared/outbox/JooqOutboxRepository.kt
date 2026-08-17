package com.hyperscale.commerce.modules.shared.outbox

import com.hyperscale.commerce.jooq.order.Tables.OUTBOX_EVENTS
import java.time.Instant
import java.time.ZoneOffset
import org.jooq.DSLContext
import org.jooq.impl.DSL

class JooqOutboxRepository(private val dsl: DSLContext) : OutboxRepository {

  override fun insert(aggregateId: String, eventType: String, payload: String): Long {
    return dsl.insertInto(OUTBOX_EVENTS)
        .columns(OUTBOX_EVENTS.AGGREGATE_ID, OUTBOX_EVENTS.EVENT_TYPE, OUTBOX_EVENTS.PAYLOAD)
        .values(
            DSL.value(aggregateId),
            DSL.value(eventType),
            DSL.cast(DSL.value(payload), OUTBOX_EVENTS.PAYLOAD.dataType),
        )
        .returningResult(OUTBOX_EVENTS.ID)
        .fetchOne()
        ?.getValue(OUTBOX_EVENTS.ID) ?: error("outbox insert returned no id")
  }

  override fun claimDue(limit: Int): List<OutboxEvent> {
    return dsl.select(
            OUTBOX_EVENTS.ID,
            OUTBOX_EVENTS.AGGREGATE_ID,
            OUTBOX_EVENTS.EVENT_TYPE,
            OUTBOX_EVENTS.PAYLOAD,
            OUTBOX_EVENTS.CREATED_AT,
            OUTBOX_EVENTS.PUBLISHED_AT,
        )
        .from(OUTBOX_EVENTS)
        .where(OUTBOX_EVENTS.PUBLISHED_AT.isNull)
        .orderBy(OUTBOX_EVENTS.CREATED_AT)
        .limit(limit)
        .forUpdate()
        .skipLocked()
        .fetch { record ->
          OutboxEvent(
              id = record.getValue(OUTBOX_EVENTS.ID),
              aggregateId = record.getValue(OUTBOX_EVENTS.AGGREGATE_ID),
              eventType = record.getValue(OUTBOX_EVENTS.EVENT_TYPE),
              payload = record.getValue(OUTBOX_EVENTS.PAYLOAD).data(),
              createdAt = record.getValue(OUTBOX_EVENTS.CREATED_AT).toInstant(),
              publishedAt = record.getValue(OUTBOX_EVENTS.PUBLISHED_AT)?.toInstant(),
          )
        }
  }

  override fun markPublished(id: Long) {
    dsl.update(OUTBOX_EVENTS)
        .set(OUTBOX_EVENTS.PUBLISHED_AT, DSL.currentOffsetDateTime())
        .where(OUTBOX_EVENTS.ID.eq(id))
        .execute()
  }

  override fun markPublished(ids: Collection<Long>) {
    if (ids.isEmpty()) return
    dsl.update(OUTBOX_EVENTS)
        .set(OUTBOX_EVENTS.PUBLISHED_AT, DSL.currentOffsetDateTime())
        .where(OUTBOX_EVENTS.ID.`in`(ids))
        .execute()
  }

  override fun oldestUnpublishedAgeSeconds(): Double {
    val ageSeconds =
        DSL.field("extract(epoch from (now() - {0}))", Double::class.java, OUTBOX_EVENTS.CREATED_AT)
    return dsl.select(DSL.coalesce(ageSeconds, 0.0))
        .from(OUTBOX_EVENTS)
        .where(OUTBOX_EVENTS.PUBLISHED_AT.isNull)
        .orderBy(OUTBOX_EVENTS.CREATED_AT)
        .limit(1)
        .fetchOne(0, Double::class.java) ?: 0.0
  }

  override fun prunePublished(olderThan: Instant, batchSize: Int): Int {
    val subquery =
        dsl.select(OUTBOX_EVENTS.ID)
            .from(OUTBOX_EVENTS)
            .where(
                OUTBOX_EVENTS.PUBLISHED_AT.isNotNull,
                OUTBOX_EVENTS.PUBLISHED_AT.lt(olderThan.atOffset(ZoneOffset.UTC)),
            )
            .limit(batchSize)

    return dsl.deleteFrom(OUTBOX_EVENTS).where(OUTBOX_EVENTS.ID.`in`(subquery)).execute()
  }
}
