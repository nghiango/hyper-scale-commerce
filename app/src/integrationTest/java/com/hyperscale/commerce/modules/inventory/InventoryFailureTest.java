package com.hyperscale.commerce.modules.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyperscale.commerce.config.OutboxProperties;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public class InventoryFailureTest {

  private static final String POISON_PAYLOAD = "{\"version\":1,\"orderId\":99}";
  private static final long TIMEOUT_SECONDS = 20L;
  private static final long POLL_MILLIS = 500L;

  @Container
  static final KafkaContainer kafka =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
  }

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final OutboxProperties outboxProperties;

  @Autowired
  public InventoryFailureTest(
      KafkaTemplate<String, String> kafkaTemplate, OutboxProperties outboxProperties) {
    this.kafkaTemplate = kafkaTemplate;
    this.outboxProperties = outboxProperties;
  }

  @Test
  @DisplayName("poison message lands in the DLQ after bounded retries")
  void poisonMessageLandsInTheDlqAfterBoundedRetries() throws Exception {
    kafkaTemplate
        .send(outboxProperties.topic(), "99", POISON_PAYLOAD)
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    String dlqTopic = outboxProperties.topic() + "-dlq";
    ConsumerRecord<String, String> record = awaitDlqRecord(dlqTopic);
    assertThat(record.value()).isEqualTo(POISON_PAYLOAD);
  }

  private ConsumerRecord<String, String> awaitDlqRecord(String topic) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-test-group");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(topic));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
      while (System.nanoTime() < deadline) {
        for (ConsumerRecord<String, String> record :
            consumer.poll(Duration.ofMillis(POLL_MILLIS))) {
          return record;
        }
      }
      throw new AssertionError(
          "No record in DLQ topic " + topic + " within " + TIMEOUT_SECONDS + " seconds");
    }
  }
}
