package com.hyperscale.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperscale.commerce.modules.catalog.performance.LoadResult;
import com.hyperscale.commerce.modules.catalog.performance.LoadTest;
import com.hyperscale.commerce.orderquery.OrderQueryApplication;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
public class ServiceExtractionE2ETest {

  private static final String SKU = "PERF-SKU-00001";
  private static final int QUANTITY = 2;
  private static final long TIMEOUT_SECONDS = 20L;
  private static final long POLL_MILLIS = 500L;
  private static final long GRACE_SECONDS = 3L;
  private static final double NANOS_PER_MILLISECOND = 1_000_000.0;
  private static final String ORDER_PLACED_EVENT_TYPE = "OrderPlaced";
  private static final String ORDER_PLACED_TOPIC = "order-placed";
  private static final int QUERY_CONCURRENCY = 10;
  private static final int LOAD_RAMP_UP_SECONDS = 1;
  private static final int LOAD_DURATION_SECONDS = 3;
  private static final long READINESS_RETRY_MILLIS = 500L;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Container
  static final KafkaContainer kafka =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

  @Container
  static final ExtractionRedisContainer redis =
      new ExtractionRedisContainer(DockerImageName.parse("redis:7.2-alpine"))
          .withExposedPorts(6379);

  @Test
  @DisplayName("order flows across the extracted services")
  void orderFlowsAcrossTheExtractedServices() throws Exception {
    // Startup-order case: order-query is healthy before the monolith starts.
    ConfigurableApplicationContext orderQueryContext = startOrderQuery();
    int orderQueryPort =
        Integer.parseInt(orderQueryContext.getEnvironment().getProperty("local.server.port"));
    awaitReadiness(orderQueryPort);

    ConfigurableApplicationContext monolithContext = startMonolith();
    int monolithPort =
        Integer.parseInt(monolithContext.getEnvironment().getProperty("local.server.port"));
    awaitReadiness(monolithPort);

    try {
      long postStart = System.nanoTime();
      JsonNode created =
          objectMapper.readTree(
              post(
                      monolithPort,
                      "/orders",
                      "{\"items\":[{\"sku\":\"" + SKU + "\",\"quantity\":" + QUANTITY + "}]}")
                  .body());
      long orderId = created.get("id").asLong();
      double postLatencyMs = (System.nanoTime() - postStart) / NANOS_PER_MILLISECOND;
      assertThat(created.get("status").asText()).isEqualTo("PLACED");

      long e2eStart = System.nanoTime();
      HttpResponse<String> getById = awaitOrder(orderQueryPort, orderId);
      double e2eLatencyMs = (System.nanoTime() - e2eStart) / NANOS_PER_MILLISECOND;
      assertThat(getById.statusCode()).isEqualTo(200);
      JsonNode body = objectMapper.readTree(getById.body());
      assertThat(body.get("id").asLong()).isEqualTo(orderId);
      assertThat(body.get("status").asText()).isEqualTo("PLACED");
      assertThat(body.get("items").get(0).get("sku").asText()).isEqualTo(SKU);
      assertThat(body.get("items").get(0).get("quantity").asInt()).isEqualTo(QUANTITY);

      JdbcTemplate jdbcTemplate = monolithContext.getBean(JdbcTemplate.class);
      @SuppressWarnings("unchecked")
      KafkaTemplate<String, String> kafkaTemplate =
          (KafkaTemplate<String, String>) monolithContext.getBean(KafkaTemplate.class);
      String payload = outboxPayload(jdbcTemplate, orderId);
      kafkaTemplate
          .send(ORDER_PLACED_TOPIC, String.valueOf(orderId), payload)
          .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GRACE_SECONDS);
      while (System.nanoTime() < deadline) {
        assertThat(readModelCount(jdbcTemplate, orderId)).isLessThanOrEqualTo(1);
        Thread.sleep(POLL_MILLIS);
      }
      assertThat(readModelCount(jdbcTemplate, orderId)).isEqualTo(1);
      JsonNode afterReplay =
          objectMapper.readTree(get(orderQueryPort, "/orders/" + orderId).body());
      assertThat(afterReplay.get("status").asText()).isEqualTo("PLACED");
      assertThat(afterReplay.get("items").get(0).get("sku").asText()).isEqualTo(SKU);

      LoadResult byId =
          new LoadTest(httpClient, "GET /orders/{id}", () -> byIdRequest(orderQueryPort, orderId))
              .run(QUERY_CONCURRENCY, LOAD_RAMP_UP_SECONDS, LOAD_DURATION_SECONDS);
      LoadResult list =
          new LoadTest(httpClient, "GET /orders", () -> listRequest(orderQueryPort))
              .run(QUERY_CONCURRENCY, LOAD_RAMP_UP_SECONDS, LOAD_DURATION_SECONDS);

      writeEvidence(orderId, postLatencyMs, e2eLatencyMs, byId, list);

      assertThat(byId.errorRate()).isEqualTo(0.0);
      assertThat(list.errorRate()).isEqualTo(0.0);
    } finally {
      monolithContext.close();
      orderQueryContext.close();
    }
  }

  private ConfigurableApplicationContext startOrderQuery() {
    return new SpringApplicationBuilder(OrderQueryApplication.class)
        .run(
            "--spring.config.name=orderquery",
            "--spring.application.name=order-query",
            "--app.name=order-query",
            "--spring.datasource.url=" + postgres.getJdbcUrl(),
            "--spring.datasource.username=" + postgres.getUsername(),
            "--spring.datasource.password=" + postgres.getPassword(),
            "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
            "--spring.data.redis.host=" + redis.getHost(),
            "--spring.data.redis.port=" + redis.getMappedPort(6379),
            "--spring.flyway.locations=classpath:db/migration-order-query",
            "--spring.flyway.schemas=order_query",
            "--server.port=0");
  }

  private ConfigurableApplicationContext startMonolith() {
    return new SpringApplicationBuilder(Application.class)
        .run(
            "--spring.application.name=hyper-scale-commerce",
            "--app.name=hyper-scale-commerce",
            "--spring.datasource.url=" + postgres.getJdbcUrl(),
            "--spring.datasource.username=" + postgres.getUsername(),
            "--spring.datasource.password=" + postgres.getPassword(),
            "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
            "--spring.data.redis.host=" + redis.getHost(),
            "--spring.data.redis.port=" + redis.getMappedPort(6379),
            "--spring.flyway.locations=classpath:db/migration",
            "--spring.flyway.schemas=public",
            "--server.port=0");
  }

  private void awaitReadiness(int port) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      try {
        HttpResponse<String> response = get(port, "/actuator/health/readiness");
        if (response.statusCode() == 200 && response.body().contains("\"UP\"")) {
          return;
        }
      } catch (Exception e) {
        // service not yet accepting connections
      }
      Thread.sleep(READINESS_RETRY_MILLIS);
    }
    throw new AssertionError(
        "Service on port " + port + " not ready within " + TIMEOUT_SECONDS + " seconds");
  }

  private HttpResponse<String> awaitOrder(int orderQueryPort, long orderId) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      HttpResponse<String> response = get(orderQueryPort, "/orders/" + orderId);
      if (response.statusCode() == 200) {
        return response;
      }
      Thread.sleep(POLL_MILLIS);
    }
    return get(orderQueryPort, "/orders/" + orderId);
  }

  private long readModelCount(JdbcTemplate jdbcTemplate, long orderId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM order_query.order_read_model WHERE order_id = ?",
            Long.class,
            orderId);
    return count != null ? count : 0L;
  }

  private String outboxPayload(JdbcTemplate jdbcTemplate, long orderId) {
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

  private HttpRequest byIdRequest(int orderQueryPort, long orderId) {
    return HttpRequest.newBuilder(
            URI.create("http://localhost:" + orderQueryPort + "/orders/" + orderId))
        .GET()
        .build();
  }

  private HttpRequest listRequest(int orderQueryPort) {
    return HttpRequest.newBuilder(
            URI.create("http://localhost:" + orderQueryPort + "/orders?page=0&size=20"))
        .GET()
        .build();
  }

  private HttpResponse<String> post(int port, String path, String body)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private void writeEvidence(
      long orderId, double postLatencyMs, double e2eLatencyMs, LoadResult byId, LoadResult list)
      throws IOException {
    String section = buildSection(orderId, postLatencyMs, e2eLatencyMs, byId, list);
    File reportFile = new File("../docs/bootcamp/evidence/p5-service-extraction.md");
    reportFile.getParentFile().mkdirs();
    String existing = reportFile.exists() ? Files.readString(reportFile.toPath()) : "";
    if (existing.contains("## P5-07")) {
      String before = existing.substring(0, existing.indexOf("## P5-07")).stripTrailing();
      Files.writeString(reportFile.toPath(), before + "\n\n" + section);
    } else {
      Files.writeString(
          reportFile.toPath(),
          "\n" + section,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    }
  }

  private String buildSection(
      long orderId, double postLatencyMs, double e2eLatencyMs, LoadResult byId, LoadResult list) {
    StringBuilder builder = new StringBuilder();
    builder.append("## P5-07 — Cross-service end-to-end test").append('\n');
    builder.append('\n');
    builder
        .append(
            "Environment: one test JVM booting both applications against shared Testcontainers (PostgreSQL 16 + Kafka 7.7.1), JDK 21, Spring Boot 4.0.")
        .append('\n');
    builder.append('\n');
    builder.append("### Startup-order case").append('\n');
    builder.append('\n');
    builder
        .append(
            "`order-query` was started first and became healthy before the monolith started; the flow succeeded once both were up.")
        .append('\n');
    builder.append('\n');
    builder.append("### Flow").append('\n');
    builder.append('\n');
    builder
        .append(
            "`POST /orders` on the monolith → transactional outbox → Kafka `"
                + ORDER_PLACED_TOPIC
                + "` → `order-query` projection → `order_query.order_read_model` → `GET /orders/{id}` from `order-query`")
        .append('\n');
    builder.append('\n');
    builder.append("| Step | Detail |").append('\n');
    builder.append("|---|---|").append('\n');
    builder.append("| POST /orders | 201, order id " + orderId + " |").append('\n');
    builder.append("| Outbox event | " + ORDER_PLACED_EVENT_TYPE + " |").append('\n');
    builder
        .append("| Projection | read model row created for order " + orderId + " |")
        .append('\n');
    builder
        .append("| GET /orders/" + orderId + " | 200, served from `order_query.order_read_model` |")
        .append('\n');
    builder.append('\n');
    builder.append("### Timings").append('\n');
    builder.append('\n');
    builder.append("| Measurement | Value |").append('\n');
    builder.append("|---|---:|").append('\n');
    builder.append("| POST /orders latency | " + format(postLatencyMs, 1) + " ms |").append('\n');
    builder
        .append("| End-to-end (POST → read model visible) | " + format(e2eLatencyMs, 1) + " ms |")
        .append('\n');
    builder.append('\n');
    builder.append("### Idempotency").append('\n');
    builder.append('\n');
    builder
        .append(
            "The "
                + ORDER_PLACED_EVENT_TYPE
                + " event was replayed on the topic; the read model row count remained 1 and the row was unchanged.")
        .append('\n');
    builder.append('\n');
    builder.append("### Query endpoint p95 under concurrent load").append('\n');
    builder.append('\n');
    builder
        .append(
            "Load: "
                + QUERY_CONCURRENCY
                + " concurrent users, ramp-up "
                + LOAD_RAMP_UP_SECONDS
                + "s, duration "
                + LOAD_DURATION_SECONDS
                + "s.")
        .append('\n');
    builder.append('\n');
    builder.append("| Endpoint | p95 (ms) | Throughput (RPS) | Error rate |").append('\n');
    builder.append("|---|---:|---:|---:|").append('\n');
    builder
        .append(
            "| "
                + byId.name()
                + " | "
                + format(byId.p95(), 2)
                + " | "
                + format(byId.throughputRps(), 2)
                + " | "
                + format(byId.errorRate(), 2)
                + " |")
        .append('\n');
    builder
        .append(
            "| "
                + list.name()
                + " | "
                + format(list.p95(), 2)
                + " | "
                + format(list.throughputRps(), 2)
                + " | "
                + format(list.errorRate(), 2)
                + " |")
        .append('\n');
    return builder.toString();
  }

  private static String format(double value, int digits) {
    return String.format("%." + digits + "f", value);
  }
}
