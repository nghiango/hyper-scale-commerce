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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
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
public class CorrelationIdIntegrationTest {

  private static final String SKU = "PERF-SKU-00001";
  private static final int QUANTITY = 2;
  private static final long TIMEOUT_SECONDS = 30L;
  private static final long POLL_MILLIS = 500L;
  private static final int KAFKA_HOST_PORT = 29097;
  private static final int KAFKA_CONTAINER_PORT = 9093;
  private static final int POSTGRES_HOST_PORT = 5437;
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
  @DisplayName("correlation id propagates through HTTP and Kafka")
  void correlationIdPropagatesThroughHttpAndKafka() throws Exception {
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

      assertThat(orderResponse.headers().firstValue("X-Correlation-Id")).isPresent();

      awaitOrder(orderQueryPort, orderId);
      HttpResponse<String> getResponse = get(orderQueryPort, "/orders/" + orderId);
      assertThat(getResponse.statusCode()).isEqualTo(200);
      assertThat(getResponse.headers().firstValue("X-Correlation-Id")).isPresent();

      String recordCorrelationId = consumeCorrelationId(orderId);
      assertThat(recordCorrelationId).isNotNull();
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
      HttpResponse<String> response = get(port, "/orders/" + orderId);
      if (response.statusCode() == 200) {
        return;
      }
      Thread.sleep(POLL_MILLIS);
    }
    throw new AssertionError(
        "Order " + orderId + " not visible in read model within " + TIMEOUT_SECONDS + " seconds");
  }

  private String consumeCorrelationId(long orderId) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "correlation-test-" + System.currentTimeMillis());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of("order-placed"));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
      while (System.nanoTime() < deadline) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
        for (ConsumerRecord<String, String> record : records.records("order-placed")) {
          if (String.valueOf(orderId).equals(record.key())) {
            Header header =
                record
                    .headers()
                    .lastHeader(CorrelationIdRecordInterceptor.CORRELATION_ID_RECORD_HEADER);
            return header != null && header.value() != null
                ? new String(header.value(), StandardCharsets.UTF_8)
                : null;
          }
        }
      }
    }
    return null;
  }

  private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
