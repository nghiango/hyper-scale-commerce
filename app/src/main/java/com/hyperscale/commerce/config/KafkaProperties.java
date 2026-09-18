package com.hyperscale.commerce.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "spring.kafka")
public record KafkaProperties(@NotBlank @DefaultValue("localhost:9092") String bootstrapServers) {

  public KafkaProperties {
    if (bootstrapServers == null) {
      bootstrapServers = "localhost:9092";
    }
  }
}
