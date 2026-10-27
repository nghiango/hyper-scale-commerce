package com.hyperscale.commerce.config.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
public class CacheInvalidationBroadcastIntegrationTest {

  @Container
  static final KafkaContainer kafka =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

  @Test
  @DisplayName("one invalidation event reaches two independent pod consumer groups")
  void oneInvalidationEventReachesTwoIndependentPodConsumerGroups() throws Exception {
    createTopic();
    KafkaConsumer<String, String> firstConsumer = consumer("cache-pod-a-" + UUID.randomUUID());
    KafkaConsumer<String, String> secondConsumer = consumer("cache-pod-b-" + UUID.randomUUID());
    DefaultKafkaProducerFactory<String, String> producerFactory =
        new DefaultKafkaProducerFactory<>(
            Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.ACKS_CONFIG,
                "all"));

    try {
      firstConsumer.subscribe(List.of(CacheInvalidationService.TOPIC_CATALOG_CACHE_INVALIDATION));
      secondConsumer.subscribe(List.of(CacheInvalidationService.TOPIC_CATALOG_CACHE_INVALIDATION));
      awaitAssignment(firstConsumer);
      awaitAssignment(secondConsumer);

      NearCache<Long, CacheValue> firstCache =
          new NearCache<>("catalog_products", CacheValue.class);
      NearCache<Long, CacheValue> secondCache =
          new NearCache<>("catalog_products", CacheValue.class);
      firstCache.put(42L, new CacheValue(42L, "stale"));
      secondCache.put(42L, new CacheValue(42L, "stale"));
      CacheInvalidationService firstService = new CacheInvalidationService();
      firstService.registerCache(firstCache);
      CacheInvalidationService secondService = new CacheInvalidationService();
      secondService.registerCache(secondCache);

      CacheInvalidationService publisher =
          new CacheInvalidationService(new KafkaTemplate<>(producerFactory));
      publisher.publishInvalidation("catalog_products", "warmup");
      awaitRecord(firstConsumer);
      awaitRecord(secondConsumer);

      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        Future<String> firstRecord = executor.submit(() -> awaitRecord(firstConsumer));
        Future<String> secondRecord = executor.submit(() -> awaitRecord(secondConsumer));
        long startedAt = System.nanoTime();
        publisher.publishInvalidation("catalog_products", "42");

        firstService.onInvalidationMessage(firstRecord.get(5, TimeUnit.SECONDS));
        secondService.onInvalidationMessage(secondRecord.get(5, TimeUnit.SECONDS));
        long propagationMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertThat(firstCache.getL1Cache().getIfPresent(42L)).isNull();
        assertThat(secondCache.getL1Cache().getIfPresent(42L)).isNull();
        assertThat(propagationMillis).isLessThan(50L);
      } finally {
        executor.shutdownNow();
      }
    } finally {
      firstConsumer.close();
      secondConsumer.close();
      producerFactory.destroy();
    }
  }

  private void createTopic() throws Exception {
    try (AdminClient admin =
        AdminClient.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
      admin
          .createTopics(
              List.of(
                  new NewTopic(
                      CacheInvalidationService.TOPIC_CATALOG_CACHE_INVALIDATION, 1, (short) 1)))
          .all()
          .get();
    }
  }

  private KafkaConsumer<String, String> consumer(String groupId) {
    Properties properties = new Properties();
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    return new KafkaConsumer<>(properties);
  }

  private void awaitAssignment(KafkaConsumer<String, String> consumer) {
    for (int i = 0; i < 50; i++) {
      consumer.poll(Duration.ofMillis(100));
      if (!consumer.assignment().isEmpty()) {
        return;
      }
    }
    throw new IllegalStateException("Kafka consumer group did not receive a partition assignment");
  }

  private String awaitRecord(KafkaConsumer<String, String> consumer) {
    for (int i = 0; i < 50; i++) {
      ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
      if (!records.isEmpty()) {
        return records.iterator().next().value();
      }
    }
    throw new IllegalStateException("Kafka invalidation event was not received");
  }

  private record CacheValue(long id, String name) {}
}
