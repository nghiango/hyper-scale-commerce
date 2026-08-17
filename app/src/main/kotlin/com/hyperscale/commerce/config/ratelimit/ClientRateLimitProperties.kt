package com.hyperscale.commerce.config.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.rate-limiting")
data class ClientRateLimitProperties(
    val enabled: Boolean = true,
    val limitPerMinute: Int = 3000,
    val retryAfterSeconds: Int = 60,
)
