package com.hyperscale.commerce.modules.shared.outbox;

import static com.hyperscale.commerce.jooq.order.Tables.OUTBOX_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperscale.commerce.config.OutboxProperties;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public class OutboxRelayIntegrationTest {

  private static final String EVENT_TYPE = "OrderPlaced";
  private static final String FIRST_AGGREGATE_ID = "order-1";
  private static final String SECOND_AGGREGATE_ID = "order-2";
  private static final String RELAY_AGGREGATE_ID = "order-42";
  private static final String PAYLOAD =
      "{\"version\":1,\"eventId\":\"event-1\",\"orderId\":1,\"items\":[{\"sku\":\"PERF-SKU-00001\",\"quantity\":1}]}";
  private static final String RELAY_PAYLOAD =
      "{\"version\":1,\"eventId\":\"event-2\",\"orderId\":42,\"items\":[{\"sku\":\"PERF-SKU-00001\",\"quantity\":2}]}";
  private static final String CONSUMER_GROUP = "outbox-test-group";
  private static final long TIMEOUT_SECONDS = 10L;
  private static final long POLL_MILLIS = 500L;
  private static final int CLAIM_LIMIT = 10;
  private static final long RELAY_INTERVAL_MS_DISABLED = 3_600_000L;

  @Container
  static final KafkaContainer kafka =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("app.outbox.relay-interval-ms", () -> RELAY_INTERVAL_MS_DISABLED);
  }

  private final OutboxRepository outboxRepository;
  private final OutboxRelay outboxRelay;
  private final OutboxProperties outboxProperties;
  private final PlatformTransactionManager transactionManager;
  private final DSLContext dsl;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Autowired
  public OutboxRelayIntegrationTest(
      OutboxRepository outboxRepository,
      OutboxRelay outboxRelay,
      OutboxProperties outboxProperties,
      PlatformTransactionManager transactionManager,
      DSLContext dsl) {
    this.outboxRepository = outboxRepository;
    this.outboxRelay = outboxRelay;
    this.outboxProperties = outboxProperties;
    this.transactionManager = transactionManager;
    this.dsl = dsl;
  }

  @BeforeEach
  void cleanDb() {
    dsl.deleteFrom(OUTBOX_EVENTS).execute();
  }

  @Test
  @DisplayName("inserts and claims due outbox events in order")
  void insertsAndClaimsDueOutboxEventsInOrder() throws Exception {
    long firstId = outboxRepository.insert(FIRST_AGGREGATE_ID, EVENT_TYPE, PAYLOAD);
    long secondId = outboxRepository.insert(SECOND_AGGREGATE_ID, EVENT_TYPE, PAYLOAD);

    List<OutboxEvent> claimed = outboxRepository.claimDue(1);
    assertThat(claimed).hasSize(1);
    assertThat(claimed.get(0).id()).isEqualTo(firstId);
    assertThat(claimed.get(0).aggregateId()).isEqualTo(FIRST_AGGREGATE_ID);
    assertThat(claimed.get(0).eventType()).isEqualTo(EVENT_TYPE);
    assertThat(objectMapper.readTree(claimed.get(0).payload()))
        .isEqualTo(objectMapper.readTree(PAYLOAD));
    assertThat(claimed.get(0).publishedAt()).isNull();

    outboxRepository.markPublished(firstId);
    List<OutboxEvent> remaining = outboxRepository.claimDue(CLAIM_LIMIT);
    assertThat(remaining.stream().map(OutboxEvent::id).toList()).contains(secondId);
  }

  @Test
  @DisplayName("parallel workers claim non-overlapping batches via SKIP LOCKED")
  void parallelWorkersClaimNonOverlappingBatchesViaSkipLocked() throws Exception {
    outboxRepository.insert("skip-1", EVENT_TYPE, PAYLOAD);
    outboxRepository.insert("skip-2", EVENT_TYPE, PAYLOAD);
    outboxRepository.insert("skip-3", EVENT_TYPE, PAYLOAD);
    outboxRepository.insert("skip-4", EVENT_TYPE, PAYLOAD);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch worker1ClaimedLatch = new CountDownLatch(1);
    CountDownLatch worker2DoneLatch = new CountDownLatch(1);

    AtomicReference<List<Long>> worker1Batch = new AtomicReference<>(List.of());
    AtomicReference<List<Long>> worker2Batch = new AtomicReference<>(List.of());

    executor.submit(
        () -> {
          new TransactionTemplate(transactionManager)
              .execute(
                  status -> {
                    List<OutboxEvent> claimed = outboxRepository.claimDue(2);
                    worker1Batch.set(claimed.stream().map(OutboxEvent::id).toList());
                    worker1ClaimedLatch.countDown();
                    // hold lock briefly until worker 2 claims
                    try {
                      worker2DoneLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      throw new IllegalStateException(e);
                    }
                    return null;
                  });
        });

    worker1ClaimedLatch.await(5, TimeUnit.SECONDS);

    executor.submit(
        () -> {
          new TransactionTemplate(transactionManager)
              .execute(
                  status -> {
                    List<OutboxEvent> claimed = outboxRepository.claimDue(2);
                    worker2Batch.set(claimed.stream().map(OutboxEvent::id).toList());
                    worker2DoneLatch.countDown();
                    return null;
                  });
        });

    worker2DoneLatch.await(5, TimeUnit.SECONDS);
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);

    assertThat(worker1Batch.get()).hasSize(2);
    assertThat(worker2Batch.get()).hasSize(2);
    // Zero overlap between concurrent worker batches
    assertThat(worker1Batch.get()).doesNotContainAnyElementsOf(worker2Batch.get());
  }

  @Test
  @DisplayName("relay publishes due events to Kafka and marks them published")
  void relayPublishesDueEventsToKafkaAndMarksThemPublished() throws Exception {
    long eventId = outboxRepository.insert(RELAY_AGGREGATE_ID, EVENT_TYPE, RELAY_PAYLOAD);

    outboxRelay.publishDueEvents();

    String received = consumeFirst(RELAY_AGGREGATE_ID);
    assertThat(objectMapper.readTree(received)).isEqualTo(objectMapper.readTree(RELAY_PAYLOAD));

    List<OutboxEvent> remaining = outboxRepository.claimDue(CLAIM_LIMIT);
    assertThat(remaining.stream().map(OutboxEvent::id).toList()).doesNotContain(eventId);
  }

  private String consumeFirst(String key) {
    Properties props = new Properties();
    props.put("bootstrap.servers", kafka.getBootstrapServers());
    props.put("group.id", CONSUMER_GROUP);
    props.put("key.deserializer", StringDeserializer.class.getName());
    props.put("value.deserializer", StringDeserializer.class.getName());
    props.put("auto.offset.reset", "earliest");

    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(outboxProperties.topic()));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
      while (System.nanoTime() < deadline) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(POLL_MILLIS));
        for (ConsumerRecord<String, String> record : records) {
          if (key.equals(record.key())) {
            return record.value();
          }
        }
      }
      throw new AssertionError(
          "no message received for key " + key + " within " + TIMEOUT_SECONDS + "s");
    }
  }
}
