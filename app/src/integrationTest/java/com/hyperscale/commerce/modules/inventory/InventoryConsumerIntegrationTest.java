package com.hyperscale.commerce.modules.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyperscale.commerce.config.OutboxProperties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
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
public class InventoryConsumerIntegrationTest {

  private static final String EVENT_ID = "event-1";
  private static final long ORDER_ID = 42L;
  private static final String SKU = "PERF-SKU-00001";
  private static final int QUANTITY = 2;
  private static final long TIMEOUT_SECONDS = 15L;
  private static final long POLL_MILLIS = 500L;
  private static final long GRACE_SECONDS = 3L;

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
  private final JdbcTemplate jdbcTemplate;
  private final OutboxProperties outboxProperties;

  @Autowired
  public InventoryConsumerIntegrationTest(
      KafkaTemplate<String, String> kafkaTemplate,
      JdbcTemplate jdbcTemplate,
      OutboxProperties outboxProperties) {
    this.kafkaTemplate = kafkaTemplate;
    this.jdbcTemplate = jdbcTemplate;
    this.outboxProperties = outboxProperties;
  }

  @Test
  @DisplayName("consumes OrderPlaced and records reservations idempotently")
  void consumesOrderPlacedAndRecordsReservationsIdempotently() throws Exception {
    String payload =
        "{\"version\":1,\"eventId\":\""
            + EVENT_ID
            + "\",\"orderId\":"
            + ORDER_ID
            + ",\"items\":[{\"sku\":\""
            + SKU
            + "\",\"quantity\":"
            + QUANTITY
            + "}]}";

    kafkaTemplate
        .send(outboxProperties.topic(), String.valueOf(ORDER_ID), payload)
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    awaitReservationCount(1);

    kafkaTemplate
        .send(outboxProperties.topic(), String.valueOf(ORDER_ID), payload)
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GRACE_SECONDS);
    while (System.nanoTime() < deadline) {
      assertThat(reservationCount()).isLessThanOrEqualTo(1);
      Thread.sleep(POLL_MILLIS);
    }
    assertThat(reservationCount()).isEqualTo(1);
  }

  private void awaitReservationCount(long expected) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      if (reservationCount() == expected) {
        return;
      }
      Thread.sleep(POLL_MILLIS);
    }
    assertThat(reservationCount()).isEqualTo(expected);
  }

  private long reservationCount() {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM inventory.reservations WHERE event_id = ?", Long.class, EVENT_ID);
    return count != null ? count : 0L;
  }
}
