package com.hyperscale.commerce.orderquery.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProjectionConsumerFailureTest {

  private static final long POISON_ORDER_ID = 99L;
  private static final long VALID_ORDER_ID = 100L;
  private static final String POISON_PAYLOAD =
      "{\"version\":1,\"orderId\":" + POISON_ORDER_ID + "}";
  private static final String SKU = "PERF-SKU-00001";
  private static final long TIMEOUT_SECONDS = 30L;
  private static final long POLL_MILLIS = 500L;
  private static final long MIN_RETRY_WINDOW_MS = 300L;

  @Container
  static final KafkaContainer kafka =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
  }

  private final int port;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final JdbcTemplate jdbcTemplate;
  private final String topic;

  @Autowired
  ProjectionConsumerFailureTest(
      @LocalServerPort int port,
      KafkaTemplate<String, String> kafkaTemplate,
      JdbcTemplate jdbcTemplate,
      @Value("${app.outbox.topic}") String topic) {
    this.port = port;
    this.kafkaTemplate = kafkaTemplate;
    this.jdbcTemplate = jdbcTemplate;
    this.topic = topic;
  }

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  @DisplayName(
      "poison message lands in the DLQ after bounded retries and valid messages still project")
  void poisonMessageLandsInTheDlqAfterBoundedRetriesAndValidMessagesStillProject()
      throws Exception {
    long startedAt = System.nanoTime();
    kafkaTemplate
        .send(topic, Long.toString(POISON_ORDER_ID), POISON_PAYLOAD)
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    ConsumerRecord<String, String> dlqRecord = awaitDlqRecord(topic + "-dlq");
    assertThat(dlqRecord.value()).isEqualTo(POISON_PAYLOAD);
    assertThat(readModelCount(POISON_ORDER_ID)).isZero();

    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    assertThat(elapsedMs).isGreaterThanOrEqualTo(MIN_RETRY_WINDOW_MS);

    String validPayload =
        "{\"version\":1,\"eventId\":\"p6-05-"
            + VALID_ORDER_ID
            + "\",\"orderId\":"
            + VALID_ORDER_ID
            + ",\"status\":\"PLACED\",\"createdAt\":\"2026-08-14T00:00:00Z\",\"items\":[{\"sku\":\""
            + SKU
            + "\",\"quantity\":1}]}";
    kafkaTemplate
        .send(topic, Long.toString(VALID_ORDER_ID), validPayload)
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    awaitReadModelCount(VALID_ORDER_ID, 1);
    assertThat(awaitOrder(VALID_ORDER_ID).statusCode()).isEqualTo(200);

    String metrics = get("/actuator/prometheus").body();
    assertThat(metrics).contains("events_dlq_total");
    assertThat(metrics).contains("topic=\"order-placed-dlq\"");
    assertThat(metrics)
        .contains(
            "events_consumed_total",
            "consumer=\"order-query\"",
            "event_type=\"OrderPlaced\"",
            "outcome=\"failed\"");
    assertThat(metrics)
        .contains(
            "events_consumed_total",
            "consumer=\"order-query\"",
            "event_type=\"OrderPlaced\"",
            "outcome=\"processed\"");
  }

  private ConsumerRecord<String, String> awaitDlqRecord(String dlqTopic) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "projection-dlq-test-group");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(dlqTopic));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
      while (System.nanoTime() < deadline) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(POLL_MILLIS));
        if (!records.isEmpty()) {
          return records.iterator().next();
        }
      }
      throw new AssertionError(
          "No record in DLQ topic " + dlqTopic + " within " + TIMEOUT_SECONDS + " seconds");
    }
  }

  private void awaitReadModelCount(long orderId, long expected) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      if (readModelCount(orderId) == expected) {
        return;
      }
      Thread.sleep(POLL_MILLIS);
    }
    assertThat(readModelCount(orderId)).isEqualTo(expected);
  }

  private long readModelCount(long orderId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM order_query.order_read_model WHERE order_id = ?",
            Long.class,
            orderId);
    return count != null ? count : 0L;
  }

  private HttpResponse<String> awaitOrder(long orderId) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      HttpResponse<String> response = get("/orders/" + orderId);
      if (response.statusCode() == 200) {
        return response;
      }
      Thread.sleep(POLL_MILLIS);
    }
    return get("/orders/" + orderId);
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
