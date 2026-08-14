package com.hyperscale.commerce.modules.shared.outbox

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.apache.kafka.common.KafkaException
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled

class OutboxRelay(
    private val outboxRepository: OutboxRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val topic: String,
    private val claimLimit: Int,
    meterRegistry: MeterRegistry,
) {
  private val logger = LoggerFactory.getLogger(OutboxRelay::class.java)
  private val eventsPublished: Counter =
      meterRegistry.counter("events_published_total", "topic", topic)

  @Scheduled(fixedDelayString = "\${app.outbox.relay-interval-ms:1000}")
  fun publishDueEvents() {
    outboxRepository.claimDue(claimLimit).forEach { event ->
      try {
        kafkaTemplate
            .send(topic, event.aggregateId, event.payload)
            .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        outboxRepository.markPublished(event.id)
        eventsPublished.increment()
      } catch (exception: KafkaException) {
        logger.warn("Failed to publish outbox event {} to topic {}", event.id, topic, exception)
      } catch (exception: ExecutionException) {
        logger.warn("Failed to publish outbox event {} to topic {}", event.id, topic, exception)
      } catch (exception: TimeoutException) {
        logger.warn("Timed out publishing outbox event {} to topic {}", event.id, topic, exception)
      } catch (exception: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
  }

  private companion object {
    const val PUBLISH_TIMEOUT_SECONDS = 10L
  }
}
