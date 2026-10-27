package com.hyperscale.commerce.modules.shared.outbox;

import com.hyperscale.commerce.jooq.order.Tables;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;

public class JooqOutboxRepository implements OutboxRepository {
  private final DSLContext dsl;

  public JooqOutboxRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public long insert(String aggregateId, String eventType, String payload) {
    Record result =
        dsl.insertInto(Tables.OUTBOX_EVENTS)
            .columns(
                Tables.OUTBOX_EVENTS.AGGREGATE_ID,
                Tables.OUTBOX_EVENTS.EVENT_TYPE,
                Tables.OUTBOX_EVENTS.PAYLOAD)
            .values(
                DSL.value(aggregateId),
                DSL.value(eventType),
                DSL.cast(DSL.value(payload), Tables.OUTBOX_EVENTS.PAYLOAD.getDataType()))
            .returningResult(Tables.OUTBOX_EVENTS.ID)
            .fetchOne();
    Long id = result != null ? result.getValue(Tables.OUTBOX_EVENTS.ID) : null;
    if (id == null) {
      throw new IllegalStateException("outbox insert returned no id");
    }
    return id;
  }

  @Override
  public List<OutboxEvent> claimDue(int limit) {
    return dsl.select(
            Tables.OUTBOX_EVENTS.ID,
            Tables.OUTBOX_EVENTS.AGGREGATE_ID,
            Tables.OUTBOX_EVENTS.EVENT_TYPE,
            Tables.OUTBOX_EVENTS.PAYLOAD,
            Tables.OUTBOX_EVENTS.CREATED_AT,
            Tables.OUTBOX_EVENTS.PUBLISHED_AT)
        .from(Tables.OUTBOX_EVENTS)
        .where(Tables.OUTBOX_EVENTS.PUBLISHED_AT.isNull())
        .orderBy(Tables.OUTBOX_EVENTS.CREATED_AT)
        .limit(limit)
        .forUpdate()
        .skipLocked()
        .fetch(
            record ->
                new OutboxEvent(
                    record.getValue(Tables.OUTBOX_EVENTS.ID),
                    record.getValue(Tables.OUTBOX_EVENTS.AGGREGATE_ID),
                    record.getValue(Tables.OUTBOX_EVENTS.EVENT_TYPE),
                    record.getValue(Tables.OUTBOX_EVENTS.PAYLOAD).data(),
                    record.getValue(Tables.OUTBOX_EVENTS.CREATED_AT).toInstant(),
                    record.getValue(Tables.OUTBOX_EVENTS.PUBLISHED_AT) != null
                        ? record.getValue(Tables.OUTBOX_EVENTS.PUBLISHED_AT).toInstant()
                        : null));
  }

  @Override
  public void markPublished(long id) {
    dsl.update(Tables.OUTBOX_EVENTS)
        .set(Tables.OUTBOX_EVENTS.PUBLISHED_AT, DSL.currentOffsetDateTime())
        .where(Tables.OUTBOX_EVENTS.ID.eq(id))
        .execute();
  }

  @Override
  public void markPublished(Collection<Long> ids) {
    if (ids.isEmpty()) return;
    dsl.update(Tables.OUTBOX_EVENTS)
        .set(Tables.OUTBOX_EVENTS.PUBLISHED_AT, DSL.currentOffsetDateTime())
        .where(Tables.OUTBOX_EVENTS.ID.in(ids))
        .execute();
  }

  @Override
  public double oldestUnpublishedAgeSeconds() {
    Field<Double> ageSeconds =
        DSL.field(
            "extract(epoch from (now() - {0}))", Double.class, Tables.OUTBOX_EVENTS.CREATED_AT);
    Double age =
        dsl.select(DSL.coalesce(ageSeconds, 0.0))
            .from(Tables.OUTBOX_EVENTS)
            .where(Tables.OUTBOX_EVENTS.PUBLISHED_AT.isNull())
            .orderBy(Tables.OUTBOX_EVENTS.CREATED_AT)
            .limit(1)
            .fetchOne(0, Double.class);
    return age != null ? age : 0.0;
  }

  @Override
  public int prunePublished(Instant olderThan, int batchSize) {
    var subquery =
        dsl.select(Tables.OUTBOX_EVENTS.ID)
            .from(Tables.OUTBOX_EVENTS)
            .where(
                Tables.OUTBOX_EVENTS.PUBLISHED_AT.isNotNull(),
                Tables.OUTBOX_EVENTS.PUBLISHED_AT.lt(olderThan.atOffset(ZoneOffset.UTC)))
            .limit(batchSize);

    return dsl.deleteFrom(Tables.OUTBOX_EVENTS)
        .where(Tables.OUTBOX_EVENTS.ID.in(subquery))
        .execute();
  }
}
