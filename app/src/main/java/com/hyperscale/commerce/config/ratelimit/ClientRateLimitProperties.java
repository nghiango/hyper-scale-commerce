package com.hyperscale.commerce.config.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.rate-limiting")
public record ClientRateLimitProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("3000") int limitPerMinute,
    @DefaultValue("60") int retryAfterSeconds) {}
