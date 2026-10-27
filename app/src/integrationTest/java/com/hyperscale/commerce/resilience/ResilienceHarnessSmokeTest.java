package com.hyperscale.commerce.resilience;

import com.hyperscale.commerce.Application;
import java.util.List;
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
public class ResilienceHarnessSmokeTest {

  private static final int KAFKA_HOST_PORT = 29093;
  private static final int KAFKA_CONTAINER_PORT = 9093;
  private static final int POSTGRES_HOST_PORT = 5433;
  private static final int POSTGRES_CONTAINER_PORT = 5432;

  @Container static final KafkaContainer kafka = kafkaContainer();

  @Container static final PostgreSQLContainer<?> postgres = postgresContainer();

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
  @DisplayName("harness stops and restarts kafka and the app recovers")
  void harnessStopsAndRestartsKafkaAndTheAppRecovers() throws Exception {
    ConfigurableApplicationContext context = startMonolith();
    int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    try {
      ResilienceHarness.awaitReadiness(port);
      ResilienceHarness.awaitHealthContains(port, "\"kafka\":{\"status\":\"UP\"}");

      ResilienceHarness.stopKafka(kafka);
      ResilienceHarness.awaitHealthContains(port, "\"kafka\":{\"status\":\"DOWN\"}");

      ResilienceHarness.startKafka(kafka);
      ResilienceHarness.awaitHealthContains(port, "\"kafka\":{\"status\":\"UP\"}");

      ResilienceHarness.writeEvidence(buildSection());
    } finally {
      context.close();
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
            "--spring.flyway.locations=classpath:db/migration",
            "--spring.flyway.schemas=public",
            "--server.port=0");
  }

  private String buildSection() {
    StringBuilder builder = new StringBuilder();
    builder.append("## P6-02 — Failure-injection test harness").append('\n');
    builder.append('\n');
    builder
        .append(
            "Environment: Testcontainers PostgreSQL 16 + Kafka 7.7.1 with fixed host ports, JDK 21, Spring Boot 4.0.")
        .append('\n');
    builder.append('\n');
    builder.append("### First experiment: Kafka stop/start").append('\n');
    builder.append('\n');
    builder
        .append(
            "The monolith was started against shared containers; Kafka was stopped and restarted using the harness.")
        .append('\n');
    builder.append('\n');
    builder.append("| Step | Result |").append('\n');
    builder.append("|---|---|").append('\n');
    builder.append("| Kafka UP before outage | health shows `kafka: UP` |").append('\n');
    builder.append("| Kafka stopped | health shows `kafka: DOWN` |").append('\n');
    builder.append("| Kafka restarted | health returns to `kafka: UP` |").append('\n');
    return builder.toString();
  }
}
