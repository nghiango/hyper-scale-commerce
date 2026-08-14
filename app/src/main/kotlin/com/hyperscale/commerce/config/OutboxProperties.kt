package com.hyperscale.commerce.config

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "app.outbox")
data class OutboxProperties(
    @field:NotBlank val topic: String,
    @field:Positive val relayIntervalMs: Long = 1000,
    @field:Positive val claimLimit: Int = 100,
)
