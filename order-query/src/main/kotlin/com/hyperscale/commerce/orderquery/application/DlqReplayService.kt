package com.hyperscale.commerce.orderquery.application

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.util.Properties
import java.util.UUID
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service

@Service
class DlqReplayService(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    @Value("\${spring.kafka.bootstrap-servers}") private val bootstrapServers: String,
    @Autowired(required = false) meterRegistry: MeterRegistry? = null,
) {

  private val logger = LoggerFactory.getLogger(DlqReplayService::class.java)

  private val replayedCounter: Counter? =
      meterRegistry?.counter("dlq_replayed_events_total", "outcome", "success")
  private val skippedCounter: Counter? =
      meterRegistry?.counter("dlq_replayed_events_total", "outcome", "skipped_max_redrives")

  fun replay(dlqTopic: String, targetTopic: String, maxRecords: Int = 100): DlqReplayResult {
    val props =
        Properties().apply {
          put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
          put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-replay-${UUID.randomUUID()}")
          put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
          put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
          put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
          put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        }

    var replayed = 0
    var skipped = 0

    KafkaConsumer<String, String>(props).use { consumer ->
      consumer.subscribe(listOf(dlqTopic))
      val records = consumer.poll(Duration.ofMillis(POLL_TIMEOUT_MS))
      for (record in records.take(maxRecords)) {
        val currentRedriveCount =
            record.headers().lastHeader(HEADER_REDRIVE_COUNT)?.value()?.let {
              String(it).toIntOrNull() ?: 0
            } ?: 0

        if (currentRedriveCount >= MAX_REDRIVES) {
          logger.warn(
              "DLQ event key={} reached max redrives ({}), skipping replay",
              record.key(),
              currentRedriveCount,
          )
          skipped++
          skippedCounter?.increment()
          continue
        }

        val producerRecord = ProducerRecord(targetTopic, record.key(), record.value())
        producerRecord
            .headers()
            .add(HEADER_REDRIVE_COUNT, (currentRedriveCount + 1).toString().toByteArray())
        producerRecord.headers().add(HEADER_ORIGINAL_DLQ, dlqTopic.toByteArray())

        kafkaTemplate.send(producerRecord).get()
        replayed++
        replayedCounter?.increment()
        logger.info(
            "Replayed DLQ event key={} from {} to {} (redrive={})",
            record.key(),
            dlqTopic,
            targetTopic,
            currentRedriveCount + 1,
        )
      }
    }

    return DlqReplayResult(
        dlqTopic = dlqTopic,
        targetTopic = targetTopic,
        replayedCount = replayed,
        skippedCount = skipped,
    )
  }

  companion object {
    const val HEADER_REDRIVE_COUNT = "X-Redrive-Count"
    const val HEADER_ORIGINAL_DLQ = "X-Original-DLQ-Topic"
    const val MAX_REDRIVES = 3
    const val POLL_TIMEOUT_MS = 2000L
  }
}

data class DlqReplayResult(
    val dlqTopic: String,
    val targetTopic: String,
    val replayedCount: Int,
    val skippedCount: Int,
)
