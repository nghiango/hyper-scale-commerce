package com.hyperscale.commerce.config.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.storage.pruning")
public record StoragePruningProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("7") long outboxRetentionDays,
    @DefaultValue("24") long idempotencyRetentionHours,
    @DefaultValue("1000") int batchSize,
    @DefaultValue("3600000") long intervalMs,
    @DefaultValue("60000") long initialDelayMs) {}
