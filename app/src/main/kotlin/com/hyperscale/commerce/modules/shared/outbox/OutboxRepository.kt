package com.hyperscale.commerce.modules.shared.outbox

import java.time.Instant

interface OutboxRepository {

  fun insert(aggregateId: String, eventType: String, payload: String): Long

  fun claimDue(limit: Int): List<OutboxEvent>

  fun markPublished(id: Long)

  fun markPublished(ids: Collection<Long>)

  fun oldestUnpublishedAgeSeconds(): Double

  fun prunePublished(olderThan: Instant, batchSize: Int): Int
}
