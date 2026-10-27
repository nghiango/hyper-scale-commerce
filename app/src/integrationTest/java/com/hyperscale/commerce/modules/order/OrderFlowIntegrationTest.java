package com.hyperscale.commerce.modules.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperscale.commerce.config.OutboxProperties;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class OrderFlowIntegrationTest {

  private static final String SKU = "PERF-SKU-00001";
  private static final int QUANTITY = 2;
  private static final long TIMEOUT_SECONDS = 20L;
  private static final long POLL_MILLIS = 500L;
  private static final long GRACE_SECONDS = 3L;
  private static final double NANOS_PER_MILLISECOND = 1_000_000.0;
  private static final String ORDER_PLACED_EVENT_TYPE = "OrderPlaced";

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

  private final int port;
  private final JdbcTemplate jdbcTemplate;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final OutboxProperties outboxProperties;
  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Autowired
  public OrderFlowIntegrationTest(
      @LocalServerPort int port,
      JdbcTemplate jdbcTemplate,
      KafkaTemplate<String, String> kafkaTemplate,
      OutboxProperties outboxProperties) {
    this.port = port;
    this.jdbcTemplate = jdbcTemplate;
    this.kafkaTemplate = kafkaTemplate;
    this.outboxProperties = outboxProperties;
  }

  @Test
  @DisplayName("order flows end to end to an inventory reservation")
  void orderFlowsEndToEndToAnInventoryReservation() throws Exception {
    long postStart = System.nanoTime();
    HttpResponse<String> response =
        post("/orders", "{\"items\":[{\"sku\":\"" + SKU + "\",\"quantity\":" + QUANTITY + "}]}");
    double postLatencyMs = (System.nanoTime() - postStart) / NANOS_PER_MILLISECOND;

    assertThat(response.statusCode()).isEqualTo(201);
    long orderId = objectMapper.readTree(response.body()).get("id").asLong();

    long e2eStart = System.nanoTime();
    ReservationRow reservation = awaitReservation(orderId);
    double e2eLatencyMs = (System.nanoTime() - e2eStart) / NANOS_PER_MILLISECOND;

    assertThat(reservation.sku()).isEqualTo(SKU);
    assertThat(reservation.quantity()).isEqualTo(QUANTITY);
    assertThat(reservation.eventId()).isNotBlank();

    String payload = outboxPayload(orderId);
    kafkaTemplate
        .send(outboxProperties.topic(), String.valueOf(orderId), payload)
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GRACE_SECONDS);
    while (System.nanoTime() < deadline) {
      assertThat(reservationCount(reservation.eventId())).isLessThanOrEqualTo(1);
      Thread.sleep(POLL_MILLIS);
    }
    assertThat(reservationCount(reservation.eventId())).isEqualTo(1);

    String report = buildReport(orderId, postLatencyMs, e2eLatencyMs, reservation.eventId());
    File reportFile = new File("../docs/bootcamp/evidence/p3-event-flow.md");
    reportFile.getParentFile().mkdirs();
    Files.writeString(reportFile.toPath(), report);
  }

  private ReservationRow awaitReservation(long orderId) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      List<ReservationRow> rows =
          jdbcTemplate.query(
              "SELECT sku, quantity, event_id FROM inventory.reservations WHERE order_id = ?",
              (rs, rowNum) ->
                  new ReservationRow(
                      rs.getString("sku"), rs.getInt("quantity"), rs.getString("event_id")),
              orderId);
      if (!rows.isEmpty()) {
        return rows.get(0);
      }
      Thread.sleep(POLL_MILLIS);
    }
    throw new AssertionError(
        "No reservation for order " + orderId + " within " + TIMEOUT_SECONDS + " seconds");
  }

  private long reservationCount(String eventId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM inventory.reservations WHERE event_id = ?", Long.class, eventId);
    return count != null ? count : 0L;
  }

  private String outboxPayload(long orderId) {
    String payload =
        jdbcTemplate.queryForObject(
            "SELECT payload FROM \"order\".outbox_events WHERE aggregate_id = ? AND event_type = ?",
            String.class,
            String.valueOf(orderId),
            ORDER_PLACED_EVENT_TYPE);
    if (payload == null) {
      throw new IllegalStateException("No outbox payload for order " + orderId);
    }
    return payload;
  }

  private String buildReport(
      long orderId, double postLatencyMs, double e2eLatencyMs, String eventId) {
    StringBuilder builder = new StringBuilder();
    builder.append("# Phase 3 — Event-Driven Flow Report").append('\n');
    builder.append('\n');
    builder
        .append(
            "Environment: local Docker PostgreSQL 16 + Kafka (Testcontainers), JDK 21, Spring Boot 4.0.")
        .append('\n');
    builder.append('\n');
    builder.append("## Flow").append('\n');
    builder.append('\n');
    builder
        .append(
            "`POST /orders` → transactional outbox → Kafka `"
                + outboxProperties.topic()
                + "` → inventory reservation")
        .append('\n');
    builder.append('\n');
    builder.append("| Step | Detail |").append('\n');
    builder.append("|---|---|").append('\n');
    builder.append("| POST /orders | 201, order id " + orderId + " |").append('\n');
    builder
        .append("| Outbox event | " + ORDER_PLACED_EVENT_TYPE + ", event id `" + eventId + "` |")
        .append('\n');
    builder.append("| Kafka topic | " + outboxProperties.topic() + " |").append('\n');
    builder
        .append("| Inventory reservation | sku " + SKU + ", quantity " + QUANTITY + " |")
        .append('\n');
    builder.append('\n');
    builder.append("## Timings").append('\n');
    builder.append('\n');
    builder.append("| Measurement | Value |").append('\n');
    builder.append("|---|---:|").append('\n');
    builder.append("| POST /orders latency | " + format(postLatencyMs, 1) + " ms |").append('\n');
    builder
        .append("| End-to-end (POST → reservation) | " + format(e2eLatencyMs, 1) + " ms |")
        .append('\n');
    builder.append('\n');
    builder.append("## Idempotency").append('\n');
    builder.append('\n');
    builder
        .append(
            "The "
                + ORDER_PLACED_EVENT_TYPE
                + " event was replayed on the topic; the reservation count remained 1.")
        .append('\n');
    return builder.toString();
  }

  private static String format(double value, int digits) {
    return String.format("%." + digits + "f", value);
  }

  private HttpResponse<String> post(String path, String body)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private record ReservationRow(String sku, int quantity, String eventId) {}
}
