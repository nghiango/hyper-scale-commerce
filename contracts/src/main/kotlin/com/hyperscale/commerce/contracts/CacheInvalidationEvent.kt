package com.hyperscale.commerce.contracts

import java.time.Instant

data class CacheInvalidationEvent(
    val cacheName: String,
    val key: String? = null,
    val timestamp: Instant = Instant.now(),
    val originInstanceId: String? = null,
)
