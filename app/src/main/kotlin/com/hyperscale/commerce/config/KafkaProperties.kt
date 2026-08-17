package com.hyperscale.commerce.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "spring.kafka")
data class KafkaProperties(
    @field:NotBlank val bootstrapServers: String = "localhost:9092",
)
