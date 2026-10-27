package com.hyperscale.commerce.orderquery.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

public class DlqReplayService {
  private static final Logger logger = LoggerFactory.getLogger(DlqReplayService.class);

  public static final String HEADER_REDRIVE_COUNT = "X-Redrive-Count";
  public static final String HEADER_ORIGINAL_DLQ = "X-Original-DLQ-Topic";
  public static final int MAX_REDRIVES = 3;
  public static final long POLL_TIMEOUT_MS = 2000L;

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final String bootstrapServers;
  private final Counter replayedCounter;
  private final Counter skippedCounter;

  public DlqReplayService(
      KafkaTemplate<String, String> kafkaTemplate,
      String bootstrapServers,
      MeterRegistry meterRegistry) {
    this.kafkaTemplate = kafkaTemplate;
    this.bootstrapServers = bootstrapServers;
    this.replayedCounter =
        meterRegistry != null
            ? meterRegistry.counter("dlq_replayed_events_total", "outcome", "success")
            : null;
    this.skippedCounter =
        meterRegistry != null
            ? meterRegistry.counter("dlq_replayed_events_total", "outcome", "skipped_max_redrives")
            : null;
  }

  public DlqReplayService(KafkaTemplate<String, String> kafkaTemplate, String bootstrapServers) {
    this(kafkaTemplate, bootstrapServers, null);
  }

  public DlqReplayResult replay(String dlqTopic, String targetTopic, int maxRecords)
      throws ExecutionException, InterruptedException {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-replay-" + UUID.randomUUID());
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

    int replayed = 0;
    int skipped = 0;

    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(dlqTopic));
      ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(POLL_TIMEOUT_MS));
      int index = 0;
      for (ConsumerRecord<String, String> record : records) {
        if (index++ >= maxRecords) {
          break;
        }
        int currentRedriveCount = 0;
        Header redriveHeader = record.headers().lastHeader(HEADER_REDRIVE_COUNT);
        if (redriveHeader != null && redriveHeader.value() != null) {
          try {
            currentRedriveCount = Integer.parseInt(new String(redriveHeader.value()));
          } catch (NumberFormatException e) {
            currentRedriveCount = 0;
          }
        }

        if (currentRedriveCount >= MAX_REDRIVES) {
          logger.warn(
              "DLQ event key={} reached max redrives ({}), skipping replay",
              record.key(),
              currentRedriveCount);
          skipped++;
          if (skippedCounter != null) {
            skippedCounter.increment();
          }
          continue;
        }

        ProducerRecord<String, String> producerRecord =
            new ProducerRecord<>(targetTopic, record.key(), record.value());
        producerRecord
            .headers()
            .add(
                HEADER_REDRIVE_COUNT,
                Integer.toString(currentRedriveCount + 1).getBytes(StandardCharsets.UTF_8));
        producerRecord
            .headers()
            .add(HEADER_ORIGINAL_DLQ, dlqTopic.getBytes(StandardCharsets.UTF_8));

        kafkaTemplate.send(producerRecord).get();
        replayed++;
        if (replayedCounter != null) {
          replayedCounter.increment();
        }
        logger.info(
            "Replayed DLQ event key={} from {} to {} (redrive={})",
            record.key(),
            dlqTopic,
            targetTopic,
            currentRedriveCount + 1);
      }
    }

    return new DlqReplayResult(dlqTopic, targetTopic, replayed, skipped);
  }

  public DlqReplayResult replay(String dlqTopic, String targetTopic)
      throws ExecutionException, InterruptedException {
    return replay(dlqTopic, targetTopic, 100);
  }
}
