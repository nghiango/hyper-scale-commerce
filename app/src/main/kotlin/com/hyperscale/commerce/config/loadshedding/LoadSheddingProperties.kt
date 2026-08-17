package com.hyperscale.commerce.config.loadshedding

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.load-shedding")
data class LoadSheddingProperties(
    var enabled: Boolean = true,
    var latencyThresholdMs: Long = 200L,
    var maxInFlightDegradable: Int = 500,
)
