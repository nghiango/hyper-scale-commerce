package com.hyperscale.commerce.modules.order.domain

import java.time.Instant

interface IdempotencyRepository {
  fun tryInsertInProgress(key: String, requestHash: String): Boolean

  fun findByKey(key: String): IdempotencyRecord?

  fun markCompleted(key: String, responseCode: Int, responseBody: String)

  fun markFailed(key: String)

  fun pruneExpired(olderThan: Instant, batchSize: Int): Int
}
