package com.hyperscale.commerce.modules.shared.outbox;

import com.hyperscale.commerce.config.OutboxProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableScheduling
public class OutboxConfig {

  @Bean
  public OutboxRepository outboxRepository(DSLContext dsl) {
    return new JooqOutboxRepository(dsl);
  }

  @Bean
  public OutboxRelay outboxRelay(
      OutboxRepository outboxRepository,
      KafkaTemplate<String, String> kafkaTemplate,
      OutboxProperties outboxProperties,
      MeterRegistry meterRegistry,
      Tracer tracer,
      ObjectMapper objectMapper) {
    return new OutboxRelay(
        outboxRepository,
        kafkaTemplate,
        outboxProperties.topic(),
        outboxProperties.claimLimit(),
        meterRegistry,
        tracer,
        objectMapper);
  }

  @Bean
  public Gauge outboxRelayLagGauge(OutboxRepository outboxRepository, MeterRegistry meterRegistry) {
    return Gauge.builder(
            "outbox_relay_lag", outboxRepository, OutboxRepository::oldestUnpublishedAgeSeconds)
        .description("Seconds since the oldest unpublished outbox event")
        .register(meterRegistry);
  }
}
