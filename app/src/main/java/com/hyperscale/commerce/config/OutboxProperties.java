package com.hyperscale.commerce.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
    @NotBlank String topic,
    @Positive @DefaultValue("100") long relayIntervalMs,
    @Positive @DefaultValue("500") int claimLimit) {}
