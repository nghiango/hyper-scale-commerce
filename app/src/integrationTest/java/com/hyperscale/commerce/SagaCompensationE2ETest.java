package com.hyperscale.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperscale.commerce.orderquery.OrderQueryApplication;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
public class SagaCompensationE2ETest {

  private static final String OOS_SKU = "SKU-OOS-OUT-OF-STOCK";
  private static final int QUANTITY = 1;
  private static final long TIMEOUT_SECONDS = 60L;
  private static final long POLL_MILLIS = 300L;
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
  static final SagaRedisContainer redis =
      new SagaRedisContainer(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

  @Test
  @DisplayName("out of stock reservation triggers saga compensation and cancels order")
  void outOfStockReservationTriggersSagaCompensationAndCancelsOrder() throws Exception {
    ConfigurableApplicationContext orderQueryContext = startOrderQuery();
    int orderQueryPort =
        Integer.parseInt(orderQueryContext.getEnvironment().getProperty("local.server.port"));
    awaitReadiness(orderQueryPort);

    ConfigurableApplicationContext monolithContext = startMonolith();
    int monolithPort =
        Integer.parseInt(monolithContext.getEnvironment().getProperty("local.server.port"));
    awaitReadiness(monolithPort);

    try {
      HttpResponse<String> postResponse =
          post(
              monolithPort,
              "/orders",
              "{\"items\":[{\"sku\":\"" + OOS_SKU + "\",\"quantity\":" + QUANTITY + "}]}");
      assertThat(postResponse.statusCode()).isEqualTo(201);
      JsonNode created = objectMapper.readTree(postResponse.body());
      long orderId = created.get("id").asLong();
      assertThat(created.get("status").asText()).isEqualTo("PLACED");

      HttpResponse<String> cancelledOrder = awaitCancelledOrder(orderQueryPort, orderId);
      JsonNode body = objectMapper.readTree(cancelledOrder.body());
      assertThat(body.get("id").asLong()).isEqualTo(orderId);
      assertThat(body.get("status").asText()).isEqualTo("CANCELLED");
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

  private HttpResponse<String> awaitCancelledOrder(int orderQueryPort, long orderId)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      HttpResponse<String> response = get(orderQueryPort, "/orders/" + orderId);
      if (response.statusCode() == 200) {
        JsonNode body = objectMapper.readTree(response.body());
        if (body.get("status").asText().equals("CANCELLED")) {
          return response;
        }
      }
      Thread.sleep(POLL_MILLIS);
    }
    return get(orderQueryPort, "/orders/" + orderId);
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
