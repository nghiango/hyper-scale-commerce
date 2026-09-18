package com.hyperscale.commerce.orderquery.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(@NotBlank String name, @NotBlank String instanceId) {
  public AppProperties {
    if (instanceId == null) {
      instanceId = "local";
    }
  }
}
