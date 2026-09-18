package com.hyperscale.commerce.modules.shared.outbox;

import java.time.Instant;

public record OutboxEvent(
    long id,
    String aggregateId,
    String eventType,
    String payload,
    Instant createdAt,
    Instant publishedAt) {}
