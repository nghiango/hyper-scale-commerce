package com.hyperscale.commerce.config.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperscale.commerce.Application;
import com.hyperscale.commerce.orderquery.OrderQueryApplication;
import com.hyperscale.commerce.resilience.ResilienceHarness;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
public class EndToEndTracingTest {

  private static final String SKU = "PERF-SKU-00001";
  private static final int QUANTITY = 2;
  private static final long TIMEOUT_SECONDS = 30L;
  private static final long POLL_MILLIS = 500L;
  private static final int KAFKA_HOST_PORT = 29098;
  private static final int KAFKA_CONTAINER_PORT = 9093;
  private static final int POSTGRES_HOST_PORT = 5438;
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
  @DisplayName("trace id flows from post to order query consumer")
  void traceIdFlowsFromPostToOrderQueryConsumer(CapturedOutput output) throws Exception {
    ConfigurableApplicationContext monolithContext = startMonolith();
    int monolithPort =
        Integer.parseInt(monolithContext.getEnvironment().getProperty("local.server.port"));
    ConfigurableApplicationContext orderQueryContext = startOrderQuery();
    int orderQueryPort =
        Integer.parseInt(orderQueryContext.getEnvironment().getProperty("local.server.port"));

    try {
      ResilienceHarness.awaitReadiness(monolithPort);
      ResilienceHarness.awaitReadiness(orderQueryPort);

      HttpResponse<String> orderResponse = postOrder(monolithPort);
      assertThat(orderResponse.statusCode()).isEqualTo(201);
      long orderId = objectMapper.readTree(orderResponse.body()).get("id").asLong();
      String postTraceId =
          orderResponse.headers().firstValue(CorrelationIdFilter.TRACE_ID_HEADER).orElseThrow();

      awaitOrder(orderQueryPort, orderId);

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
      boolean found = false;
      while (System.nanoTime() < deadline) {
        if (output.getAll().contains(postTraceId)
            && output
                .getAll()
                .contains("Consumed record from order-placed traceId=" + postTraceId)) {
          found = true;
          break;
        }
        Thread.sleep(POLL_MILLIS);
      }
      assertThat(found)
          .withFailMessage("Trace %s was not propagated to order-query logs", postTraceId)
          .isTrue();
    } finally {
      monolithContext.close();
      orderQueryContext.close();
    }
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

  private HttpResponse<String> postOrder(int port) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"items\":[{\"sku\":\"" + SKU + "\",\"quantity\":" + QUANTITY + "}]}"))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private void awaitOrder(int port, long orderId) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders/" + orderId))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        return;
      }
      Thread.sleep(POLL_MILLIS);
    }
    throw new AssertionError(
        "Order " + orderId + " not visible in read model within " + TIMEOUT_SECONDS + " seconds");
  }
}
