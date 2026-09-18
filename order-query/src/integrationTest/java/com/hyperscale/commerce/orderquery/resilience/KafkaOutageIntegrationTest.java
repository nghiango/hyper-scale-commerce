package com.hyperscale.commerce.orderquery.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyperscale.commerce.orderquery.OrderQueryApplication;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class KafkaOutageIntegrationTest {

  private static final long TIMEOUT_SECONDS = 30L;
  private static final long POLL_MILLIS = 500L;
  private static final String ORDER_PLACED_TOPIC = "order-placed";
  private static final String SKU = "PERF-SKU-00001";
  private static final int KAFKA_HOST_PORT = 29093;
  private static final int KAFKA_CONTAINER_PORT = 9093;
  private static final int POSTGRES_HOST_PORT = 5433;
  private static final int POSTGRES_CONTAINER_PORT = 5432;

  @Container static final KafkaContainer kafka = createKafka();

  @Container static final PostgreSQLContainer<?> postgres = createPostgres();

  private static KafkaContainer createKafka() {
    KafkaContainer container =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));
    container.setPortBindings(List.of(KAFKA_HOST_PORT + ":" + KAFKA_CONTAINER_PORT));
    return container;
  }

  private static PostgreSQLContainer<?> createPostgres() {
    PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16");
    container.setPortBindings(List.of(POSTGRES_HOST_PORT + ":" + POSTGRES_CONTAINER_PORT));
    return container;
  }

  @Test
  @DisplayName("starts without kafka and catches up when the broker returns")
  void startsWithoutKafkaAndCatchesUpWhenTheBrokerReturns() throws Exception {
    ResilienceHarness.stopKafka(kafka);

    ConfigurableApplicationContext context = startOrderQuery();
    int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    try {
      ResilienceHarness.awaitReadiness(port);
      ResilienceHarness.awaitHealthContains(port, "\"kafka\":{\"status\":\"DOWN\"}");

      ResilienceHarness.startKafka(kafka);
      ResilienceHarness.awaitHealthContains(port, "\"kafka\":{\"status\":\"UP\"}");

      long orderId = 1L;
      String producerBootstrap = "localhost:" + KAFKA_HOST_PORT;
      produceOrderPlaced(orderId, producerBootstrap);

      JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
      awaitReadModel(jdbcTemplate, orderId);
      assertThat(readModelCount(jdbcTemplate, orderId)).isEqualTo(1);
    } finally {
      context.close();
    }
  }

  private ConfigurableApplicationContext startOrderQuery() {
    String kafkaBootstrap = "PLAINTEXT://localhost:" + KAFKA_HOST_PORT;
    return new SpringApplicationBuilder(OrderQueryApplication.class)
        .run(
            "--spring.config.name=orderquery",
            "--spring.application.name=order-query",
            "--app.name=order-query",
            "--spring.datasource.url=" + postgres.getJdbcUrl(),
            "--spring.datasource.username=" + postgres.getUsername(),
            "--spring.datasource.password=" + postgres.getPassword(),
            "--spring.kafka.bootstrap-servers=" + kafkaBootstrap,
            "--spring.flyway.locations=classpath:db/migration-order-query",
            "--spring.flyway.schemas=order_query",
            "--server.port=0");
  }

  private void produceOrderPlaced(long orderId, String bootstrapServers) throws Exception {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
      String event =
          "{\"version\":1,\"eventId\":\"p6-03-"
              + orderId
              + "\",\"orderId\":"
              + orderId
              + ",\"status\":\"PLACED\",\"createdAt\":\""
              + Instant.now()
              + "\",\"items\":[{\"sku\":\""
              + SKU
              + "\",\"quantity\":2}]}";
      producer.send(new ProducerRecord<>(ORDER_PLACED_TOPIC, Long.toString(orderId), event)).get();
    }
  }

  private void awaitReadModel(JdbcTemplate jdbcTemplate, long orderId) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      if (readModelCount(jdbcTemplate, orderId) == 1L) {
        return;
      }
      Thread.sleep(POLL_MILLIS);
    }
    throw new AssertionError(
        "Order " + orderId + " not projected within " + TIMEOUT_SECONDS + " seconds");
  }

  private long readModelCount(JdbcTemplate jdbcTemplate, long orderId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM order_query.order_read_model WHERE order_id = ?",
            Long.class,
            orderId);
    return count != null ? count : 0L;
  }
}
