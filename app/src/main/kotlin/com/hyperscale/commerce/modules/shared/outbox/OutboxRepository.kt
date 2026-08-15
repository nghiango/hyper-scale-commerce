package com.hyperscale.commerce.modules.shared.outbox

interface OutboxRepository {

  fun insert(aggregateId: String, eventType: String, payload: String): Long

  fun claimDue(limit: Int): List<OutboxEvent>

  fun markPublished(id: Long)

  fun markPublished(ids: Collection<Long>)

  fun oldestUnpublishedAgeSeconds(): Double
}
