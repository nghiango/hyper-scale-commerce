package com.hyperscale.commerce.config.storage

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.storage.pruning")
data class StoragePruningProperties(
    val enabled: Boolean = true,
    val outboxRetentionDays: Long = 7,
    val idempotencyRetentionHours: Long = 24,
    val batchSize: Int = 1000,
    val intervalMs: Long = 3_600_000,
    val initialDelayMs: Long = 60_000,
)
