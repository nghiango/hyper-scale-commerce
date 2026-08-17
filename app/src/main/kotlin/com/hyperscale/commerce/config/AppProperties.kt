package com.hyperscale.commerce.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "app")
data class AppProperties(
    @field:NotBlank val name: String,
    @field:NotBlank val instanceId: String = "local",
)
