package com.hyperscale.commerce.orderquery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrderProjectionIntegrationTest {

  private static final String EVENT_ID = "event-1";
  private static final long ORDER_ID = 42L;
  private static final String STATUS = "PLACED";
  private static final String CREATED_AT = "2026-08-14T00:00:00Z";
  private static final String SKU = "PERF-SKU-00001";
  private static final int QUANTITY = 2;
  private static final long TIMEOUT_SECONDS = 15L;
  private static final long POLL_MILLIS = 500L;
  private static final long GRACE_SECONDS = 3L;

  @Container
  static final KafkaContainer kafka =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
  }

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final JdbcTemplate jdbcTemplate;
  private final String topic;

  @Autowired
  OrderProjectionIntegrationTest(
      KafkaTemplate<String, String> kafkaTemplate,
      JdbcTemplate jdbcTemplate,
      @Value("${app.outbox.topic}") String topic) {
    this.kafkaTemplate = kafkaTemplate;
    this.jdbcTemplate = jdbcTemplate;
    this.topic = topic;
  }

  @Test
  @DisplayName("projects OrderPlaced into the read model idempotently")
  void projectsOrderPlacedIntoTheReadModelIdempotently() throws Exception {
    String payload =
        "{\"version\":1,\"eventId\":\""
            + EVENT_ID
            + "\",\"orderId\":"
            + ORDER_ID
            + ",\"status\":\""
            + STATUS
            + "\",\"createdAt\":\""
            + CREATED_AT
            + "\",\"items\":[{\"sku\":\""
            + SKU
            + "\",\"quantity\":"
            + QUANTITY
            + "}]}";

    kafkaTemplate
        .send(topic, Long.toString(ORDER_ID), payload)
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    awaitReadModelCount(1);
    assertThat(readModelStatus()).isEqualTo(STATUS);
    assertThat(readModelItems()).contains(SKU);

    kafkaTemplate
        .send(topic, Long.toString(ORDER_ID), payload)
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GRACE_SECONDS);
    while (System.nanoTime() < deadline) {
      assertThat(readModelCount()).isLessThanOrEqualTo(1);
      Thread.sleep(POLL_MILLIS);
    }
    assertThat(readModelCount()).isEqualTo(1);
    assertThat(readModelStatus()).isEqualTo(STATUS);
    assertThat(readModelItems()).contains(SKU);
  }

  private void awaitReadModelCount(long expected) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      if (readModelCount() == expected) {
        return;
      }
      Thread.sleep(POLL_MILLIS);
    }
    assertThat(readModelCount()).isEqualTo(expected);
  }

  private long readModelCount() {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM order_query.order_read_model WHERE order_id = ?",
            Long.class,
            ORDER_ID);
    return count != null ? count : 0L;
  }

  private String readModelStatus() {
    String status =
        jdbcTemplate.queryForObject(
            "SELECT status FROM order_query.order_read_model WHERE order_id = ?",
            String.class,
            ORDER_ID);
    if (status == null) {
      throw new IllegalStateException("No read model row for order " + ORDER_ID);
    }
    return status;
  }

  private String readModelItems() {
    String items =
        jdbcTemplate.queryForObject(
            "SELECT items::text FROM order_query.order_read_model WHERE order_id = ?",
            String.class,
            ORDER_ID);
    if (items == null) {
      throw new IllegalStateException("No read model row for order " + ORDER_ID);
    }
    return items;
  }
}
