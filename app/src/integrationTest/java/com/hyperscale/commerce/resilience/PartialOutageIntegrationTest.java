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
public class PartialOutageIntegrationTest {

  private static final String SKU = "PERF-SKU-00001";
  private static final int QUANTITY = 2;
  private static final long TIMEOUT_SECONDS = 30L;
  private static final long POLL_MILLIS = 500L;
  private static final String ORDER_PLACED_EVENT_TYPE = "OrderPlaced";
  private static final int KAFKA_HOST_PORT = 29096;
  private static final int KAFKA_CONTAINER_PORT = 9093;
  private static final int POSTGRES_HOST_PORT = 5436;
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
  @DisplayName("order-query down while app writes an order catches up when order-query starts")
  void orderQueryDownWhileAppWritesAnOrderCatchesUpWhenOrderQueryStarts() throws Exception {
    ConfigurableApplicationContext monolithContext = startMonolith();
    int monolithPort =
        Integer.parseInt(monolithContext.getEnvironment().getProperty("local.server.port"));
    ResilienceHarness.awaitReadiness(monolithPort);

    try {
      long orderId = postOrder(monolithPort);

      JdbcTemplate monolithJdbc = monolithContext.getBean(JdbcTemplate.class);
      awaitOutboxPublished(monolithJdbc, orderId);

      ConfigurableApplicationContext orderQueryContext = startOrderQuery();
      int orderQueryPort =
          Integer.parseInt(orderQueryContext.getEnvironment().getProperty("local.server.port"));
      try {
        ResilienceHarness.awaitReadiness(orderQueryPort);
        awaitOrder(orderQueryPort, orderId);
      } finally {
        orderQueryContext.close();
      }
    } finally {
      monolithContext.close();
    }
  }

  @Test
  @DisplayName("app down after projection catches up and a new order flows when app restarts")
  void appDownAfterProjectionCatchesUpAndANewOrderFlowsWhenAppRestarts() throws Exception {
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

      monolithContext.close();

      assertThat(get(orderQueryPort, "/orders/" + order1).statusCode()).isEqualTo(200);

      ConfigurableApplicationContext restartedContext = startMonolith();
      monolithPort =
          Integer.parseInt(restartedContext.getEnvironment().getProperty("local.server.port"));
      ResilienceHarness.awaitReadiness(monolithPort);

      try {
        long order2 = postOrder(monolithPort);
        awaitOrder(orderQueryPort, order2);
      } finally {
        restartedContext.close();
      }
    } finally {
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
    HttpResponse<String> response =
        post(
            port,
            "/orders",
            "{\"items\":[{\"sku\":\"" + SKU + "\",\"quantity\":" + QUANTITY + "}]}");
    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode body = objectMapper.readTree(response.body());
    assertThat(body.get("status").asText()).isEqualTo("PLACED");
    return body.get("id").asLong();
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

  private void awaitOutboxPublished(JdbcTemplate jdbcTemplate, long orderId)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      Long count =
          jdbcTemplate.queryForObject(
              "SELECT count(*) FROM \"order\".outbox_events WHERE aggregate_id = ? AND event_type = ? AND published_at IS NOT NULL",
              Long.class,
              String.valueOf(orderId),
              ORDER_PLACED_EVENT_TYPE);
      if ((count != null ? count : 0L) > 0) {
        return;
      }
      Thread.sleep(POLL_MILLIS);
    }
    throw new AssertionError(
        "Outbox event for order "
            + orderId
            + " not published within "
            + TIMEOUT_SECONDS
            + " seconds");
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
}
