package com.hyperscale.commerce.modules.order.domain

import java.time.Instant

private const val DEFAULT_TTL_SECONDS = 86400L

enum class IdempotencyStatus {
  IN_PROGRESS,
  COMPLETED,
  FAILED,
}

data class IdempotencyRecord(
    val key: String,
    val requestHash: String,
    val status: IdempotencyStatus,
    val responseCode: Int? = null,
    val responseBody: String? = null,
    val createdAt: Instant = Instant.now(),
    val expiresAt: Instant = Instant.now().plusSeconds(DEFAULT_TTL_SECONDS),
)
