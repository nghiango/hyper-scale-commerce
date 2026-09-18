package com.hyperscale.commerce.config.loadshedding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.load-shedding")
public record LoadSheddingProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("200") long latencyThresholdMs,
    @DefaultValue("500") int maxInFlightDegradable) {}
