package com.hyperscale.commerce.orderquery.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "spring.kafka")
public record KafkaProperties(@NotBlank String bootstrapServers) {
  public KafkaProperties {
    if (bootstrapServers == null) {
      bootstrapServers = "localhost:9092";
    }
  }
}
