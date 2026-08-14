package com.hyperscale.commerce.modules.shared.outbox

import com.hyperscale.commerce.config.OutboxProperties
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@EnableScheduling
class OutboxConfig {

  @Bean fun outboxRepository(dsl: DSLContext): OutboxRepository = JooqOutboxRepository(dsl)

  @Bean
  fun outboxRelay(
      outboxRepository: OutboxRepository,
      kafkaTemplate: KafkaTemplate<String, String>,
      outboxProperties: OutboxProperties,
      meterRegistry: MeterRegistry,
  ): OutboxRelay =
      OutboxRelay(
          outboxRepository,
          kafkaTemplate,
          outboxProperties.topic,
          outboxProperties.claimLimit,
          meterRegistry,
      )

  @Bean
  fun outboxRelayLagGauge(
      outboxRepository: OutboxRepository,
      meterRegistry: MeterRegistry,
  ): Gauge {
    return Gauge.builder("outbox_relay_lag", outboxRepository) { repository ->
          repository.oldestUnpublishedAgeSeconds()
        }
        .description("Seconds since the oldest unpublished outbox event")
        .register(meterRegistry)
  }
}
