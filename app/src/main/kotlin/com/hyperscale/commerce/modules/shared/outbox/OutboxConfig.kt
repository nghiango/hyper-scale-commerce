package com.hyperscale.commerce.modules.shared.outbox

import com.hyperscale.commerce.config.OutboxProperties
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.tracing.Tracer
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import tools.jackson.databind.ObjectMapper

@Configuration
@EnableScheduling
class OutboxConfig {

  @Bean fun outboxRepository(dsl: DSLContext): OutboxRepository = JooqOutboxRepository(dsl)

  @Bean
  @Suppress("LongParameterList")
  fun outboxRelay(
      outboxRepository: OutboxRepository,
      kafkaTemplate: KafkaTemplate<String, String>,
      outboxProperties: OutboxProperties,
      meterRegistry: MeterRegistry,
      tracer: Tracer,
      objectMapper: ObjectMapper,
  ): OutboxRelay =
      OutboxRelay(
          outboxRepository,
          kafkaTemplate,
          outboxProperties.topic,
          outboxProperties.claimLimit,
          meterRegistry,
          tracer,
          objectMapper,
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
