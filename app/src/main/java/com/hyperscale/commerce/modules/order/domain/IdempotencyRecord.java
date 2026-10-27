package com.hyperscale.commerce.modules.order.domain;

import java.time.Instant;

public record IdempotencyRecord(
    String key,
    String requestHash,
    IdempotencyStatus status,
    Integer responseCode,
    String responseBody,
    Instant createdAt,
    Instant expiresAt) {}
