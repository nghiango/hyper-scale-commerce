package com.hyperscale.commerce.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperscale.commerce.Application;
import com.hyperscale.commerce.orderquery.OrderQueryApplication;
import com.hyperscale.commerce.testsupport.IsolatedRedisContainer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
public class KafkaOutageIntegrationTest {

  private static final String SKU = "PERF-SKU-00001";
  private static final int QUANTITY = 2;
  private static final long TIMEOUT_SECONDS = 30L;
  private static final long POLL_MILLIS = 500L;
  private static final double NANOS_PER_MILLISECOND = 1_000_000.0;
  private static final String ORDER_PLACED_EVENT_TYPE = "OrderPlaced";
  private static final int KAFKA_HOST_PORT = 29093;
  private static final int KAFKA_CONTAINER_PORT = 9093;
  private static final int POSTGRES_HOST_PORT = 5433;
  private static final int POSTGRES_CONTAINER_PORT = 5432;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Container static final KafkaContainer kafka = kafkaContainer();

  @Container static final PostgreSQLContainer<?> postgres = postgresContainer();

  @Container
  static final IsolatedRedisContainer redis =
      new IsolatedRedisContainer(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

  private static KafkaContainer kafkaContainer() {
    KafkaContainer container =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));
    container.setPortBindings(List.of(KAFKA_HOST_PORT + ":" + KAFKA_CONTAINER_PORT));
    return container;
  }

  private static PostgreSQLContainer<?> postgresContainer() {
    PostgreSQLContainer<?> container =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));
    container.setPortBindings(List.of(POSTGRES_HOST_PORT + ":" + POSTGRES_CONTAINER_PORT));
    return container;
  }

  @Test
  @DisplayName(
      "outbox buffers events while kafka is down and the projection catches up when the broker returns")
  void outboxBuffersEventsWhileKafkaIsDownAndTheProjectionCatchesUpWhenTheBrokerReturns()
      throws Exception {
    ConfigurableApplicationContext orderQueryContext = startOrderQuery();
    int orderQueryPort =
        Integer.parseInt(orderQueryContext.getEnvironment().getProperty("local.server.port"));
    ResilienceHarness.awaitReadiness(orderQueryPort);

    ConfigurableApplicationContext monolithContext = startMonolith();
    int monolithPort =
        Integer.parseInt(monolithContext.getEnvironment().getProperty("local.server.port"));
    ResilienceHarness.awaitReadiness(monolithPort);

    try {
      long order1 = postOrder(monolithPort);
      awaitOrder(orderQueryPort, order1);

      ResilienceHarness.stopKafka(kafka);
      ResilienceHarness.awaitHealthContains(monolithPort, "\"kafka\":{\"status\":\"DOWN\"}");
      ResilienceHarness.awaitHealthContains(orderQueryPort, "\"kafka\":{\"status\":\"DOWN\"}");

      long order2 = postOrder(monolithPort);
      JdbcTemplate monolithJdbc = monolithContext.getBean(JdbcTemplate.class);
      assertThat(outboxPublished(monolithJdbc, order2)).isFalse();

      ResilienceHarness.startKafka(kafka);
      ResilienceHarness.awaitHealthContains(monolithPort, "\"kafka\":{\"status\":\"UP\"}");
      ResilienceHarness.awaitHealthContains(orderQueryPort, "\"kafka\":{\"status\":\"UP\"}");

      awaitOrder(orderQueryPort, order2);
      assertThat(outboxPublished(monolithJdbc, order2)).isTrue();

      ResilienceHarness.writeEvidence(buildSection(order1, order2));
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

  private long postOrder(int port) throws Exception {
    long start = System.nanoTime();
    HttpResponse<String> response =
        post(
            port,
            "/orders",
            "{\"items\":[{\"sku\":\"" + SKU + "\",\"quantity\":" + QUANTITY + "}]}");
    double latency = (System.nanoTime() - start) / NANOS_PER_MILLISECOND;
    assertThat(response.statusCode()).isEqualTo(201);
    long orderId = objectMapper.readTree(response.body()).get("id").asLong();
    assertThat(objectMapper.readTree(response.body()).get("status").asText()).isEqualTo("PLACED");
    return orderId;
  }

  private void awaitOrder(int port, long orderId) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      HttpResponse<String> response = get(port, "/orders/" + orderId);
      if (response.statusCode() == 200) {
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("id").asLong()).isEqualTo(orderId);
        assertThat(body.get("status").asText()).isEqualTo("PLACED");
        return;
      }
      Thread.sleep(POLL_MILLIS);
    }
    throw new AssertionError(
        "Order " + orderId + " not visible in read model within " + TIMEOUT_SECONDS + " seconds");
  }

  private boolean outboxPublished(JdbcTemplate jdbcTemplate, long orderId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM \"order\".outbox_events WHERE aggregate_id = ? AND event_type = ? AND published_at IS NOT NULL",
            Long.class,
            String.valueOf(orderId),
            ORDER_PLACED_EVENT_TYPE);
    return (count != null ? count : 0L) > 0;
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

  private String buildSection(long order1, long order2) {
    StringBuilder builder = new StringBuilder();
    builder.append("## P6-03 — Kafka outage experiments").append('\n');
    builder.append('\n');
    builder
        .append(
            "Environment: one test JVM booting both applications against shared Testcontainers (PostgreSQL 16 + Kafka 7.7.1), JDK 21, Spring Boot 4.0.")
        .append('\n');
    builder.append('\n');
    builder.append("### Experiment A: projection side").append('\n');
    builder.append('\n');
    builder
        .append(
            "`order-query` was started while Kafka was up, then the broker was stopped and restarted. The projection caught up to the events published after the broker returned.")
        .append('\n');
    builder.append('\n');
    builder.append("### Experiment B: outbox side").append('\n');
    builder.append('\n');
    builder
        .append(
            "`POST /orders` while Kafka was down buffered the event in the outbox (`published = false`). After the broker returned, the relay published the event and the projection consumed it.")
        .append('\n');
    builder.append('\n');
    builder
        .append("| Order | Kafka state | Visible in read model | Outbox published |")
        .append('\n');
    builder.append("|---|---|---|---:|").append('\n');
    builder.append("| " + order1 + " | UP | yes | yes |").append('\n');
    builder
        .append("| " + order2 + " | DOWN then UP | yes (after recovery) | yes (after recovery) |")
        .append('\n');
    return builder.toString();
  }
}
