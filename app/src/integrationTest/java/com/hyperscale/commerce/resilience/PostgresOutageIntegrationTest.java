package com.hyperscale.commerce.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import com.hyperscale.commerce.Application;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
public class PostgresOutageIntegrationTest {

  private static final String SKU = "PERF-SKU-00001";
  private static final int QUANTITY = 2;
  private static final long TIMEOUT_SECONDS = 30L;
  private static final long POLL_MILLIS = 500L;
  private static final String ORDER_PLACED_EVENT_TYPE = "OrderPlaced";
  private static final int KAFKA_HOST_PORT = 29094;
  private static final int KAFKA_CONTAINER_PORT = 9093;
  private static final int POSTGRES_HOST_PORT = 5434;
  private static final int POSTGRES_CONTAINER_PORT = 5432;
  private static final Pattern EVENTS_PUBLISHED_PATTERN =
      Pattern.compile(
          "events_published_total\\{[^}]*topic=\"order-placed\"[^}]*\\}\\s+(\\d+\\.?\\d*)");

  private static final String postgresVolume = "hyperscale-outage-" + java.util.UUID.randomUUID();

  @Container static final KafkaContainer kafka = kafkaContainer();

  @Container static final PostgreSQLContainer<?> postgres = postgresContainer();

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @AfterAll
  static void removePostgresVolume() {
    postgres.stop();
    try {
      DockerClientFactory.instance().client().removeVolumeCmd(postgresVolume).exec();
    } catch (NotFoundException ignored) {
      // Startup may fail before Docker creates the volume.
    }
  }

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
    container.withCreateContainerCmdModifier(
        command ->
            command
                .getHostConfig()
                .withBinds(new Bind(postgresVolume, new Volume("/var/lib/postgresql/data"))));
    container.waitingFor(
        Wait.forLogMessage(".*database system is ready to accept connections.*", 2));
    return container;
  }

  @Test
  @DisplayName("app survives a postgres outage without losing committed data")
  void appSurvivesAPostgresOutageWithoutLosingCommittedData() throws Exception {
    ConfigurableApplicationContext monolithContext = startMonolith();
    int monolithPort =
        Integer.parseInt(monolithContext.getEnvironment().getProperty("local.server.port"));

    try {
      ResilienceHarness.awaitReadiness(monolithPort);
      awaitAggregateHealth(monolithPort, "UP");

      long order1 = postOrder(monolithPort);
      awaitEventsPublished(monolithPort, 1.0);
      JdbcTemplate jdbcTemplate = monolithContext.getBean(JdbcTemplate.class);
      awaitOutboxPublished(jdbcTemplate, order1);

      ResilienceHarness.stopPostgres(postgres);
      awaitAggregateHealth(monolithPort, "DOWN");
      ResilienceHarness.awaitReadiness(monolithPort);

      HttpResponse<String> failingResponse =
          post(
              monolithPort,
              "/orders",
              "{\"items\":[{\"sku\":\"" + SKU + "\",\"quantity\":" + QUANTITY + "}]}");
      assertThat(failingResponse.statusCode()).isGreaterThanOrEqualTo(500);

      ResilienceHarness.startPostgres(postgres);
      awaitAggregateHealth(monolithPort, "UP");

      awaitOutboxPublished(jdbcTemplate, order1);
      assertThat(eventsPublished(monolithPort)).isGreaterThanOrEqualTo(1.0);
      long order2 = postOrder(monolithPort);
      awaitEventsPublished(monolithPort, 2.0);

      ResilienceHarness.writeEvidence(buildSection(order1, order2));
    } finally {
      monolithContext.close();
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
            "--spring.datasource.hikari.connection-test-query=SELECT 1",
            "--spring.datasource.hikari.validation-timeout=2000",
            "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
            "--spring.flyway.locations=classpath:db/migration",
            "--spring.flyway.schemas=public",
            "--management.health.redis.enabled=false",
            "--server.port=0");
  }

  private long postOrder(int port) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      HttpResponse<String> response =
          post(
              port,
              "/orders",
              "{\"items\":[{\"sku\":\"" + SKU + "\",\"quantity\":" + QUANTITY + "}]}");
      if (response.statusCode() == 201) {
        long orderId = objectMapper.readTree(response.body()).get("id").asLong();
        assertThat(objectMapper.readTree(response.body()).get("status").asText())
            .isEqualTo("PLACED");
        return orderId;
      }
      Thread.sleep(POLL_MILLIS);
    }
    throw new AssertionError("Could not create an order within " + TIMEOUT_SECONDS + " seconds");
  }

  private void awaitEventsPublished(int port, double expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      if (eventsPublished(port) >= expected) {
        return;
      }
      Thread.sleep(POLL_MILLIS);
    }
    throw new AssertionError(
        "events_published_total did not reach "
            + expected
            + " within "
            + TIMEOUT_SECONDS
            + " seconds");
  }

  private double eventsPublished(int port) throws IOException, InterruptedException {
    HttpResponse<String> response = get(port, "/actuator/prometheus");
    assertThat(response.statusCode()).isEqualTo(200);
    Matcher match = EVENTS_PUBLISHED_PATTERN.matcher(response.body());
    return match.find() ? Double.parseDouble(match.group(1)) : 0.0;
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
      long published = count != null ? count : 0L;
      if (published == 1L) {
        return;
      }
      Thread.sleep(POLL_MILLIS);
    }
    throw new AssertionError("Committed outbox event for order " + orderId + " was not published");
  }

  private void awaitAggregateHealth(int port, String expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    String lastBody = "";
    while (System.nanoTime() < deadline) {
      HttpResponse<String> response = get(port, "/actuator/health");
      lastBody = response.body();
      var health = new com.fasterxml.jackson.databind.ObjectMapper().readTree(lastBody);
      if (expected.equals(health.path("status").asText())
          && expected.equals(health.path("components").path("db").path("status").asText())
          && response.statusCode() == ("UP".equals(expected) ? 200 : 503)) {
        return;
      }
      Thread.sleep(POLL_MILLIS);
    }
    throw new AssertionError(
        "Aggregate/database health did not become " + expected + ": " + lastBody);
  }

  private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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

  private String buildSection(long order1, long order2) {
    StringBuilder builder = new StringBuilder();
    builder.append("## P6-04 — PostgreSQL outage experiments").append('\n');
    builder.append('\n');
    builder
        .append(
            "Environment: Testcontainers PostgreSQL 16 + Kafka 7.7.1 with fixed host ports, JDK 21, Spring Boot 4.0.")
        .append('\n');
    builder.append('\n');
    builder.append("### Experiment A: application side").append('\n');
    builder.append('\n');
    builder
        .append(
            "The monolith was started with PostgreSQL up. `POST /orders` for order "
                + order1
                + " committed the row and the outbox was published. PostgreSQL was stopped; a subsequent `POST /orders` returned a 5xx error. After the database returned, the committed outbox row for order "
                + order1
                + " was still present, and order "
                + order2
                + " could be created and published.")
        .append('\n');
    builder.append('\n');
    builder.append("| Order | Postgres state | POST accepted | Outbox published |").append('\n');
    builder.append("|---|---|---:|:---:|").append('\n');
    builder.append("| " + order1 + " | UP | yes | yes |").append('\n');
    builder.append("| (new) | DOWN | no | — |").append('\n');
    builder.append("| " + order2 + " | UP after recovery | yes | yes |").append('\n');
    return builder.toString();
  }
}
