package com.hyperscale.commerce.orderquery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderQueryApplicationIntegrationTest {

  @Container
  static final KafkaContainer kafka =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
  }

  private final JdbcTemplate jdbcTemplate;
  private final int port;

  @Autowired
  OrderQueryApplicationIntegrationTest(
      JdbcTemplate jdbcTemplate, @Value("${local.server.port}") int port) {
    this.jdbcTemplate = jdbcTemplate;
    this.port = port;
  }

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  @DisplayName("connects to the isolated container database")
  void connectsToTheIsolatedContainerDatabase() {
    assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
  }

  @Test
  @DisplayName("flyway applies the order_query read model migration")
  void flywayAppliesTheOrderQueryReadModelMigration() {
    String version =
        jdbcTemplate.queryForObject(
            "SELECT max(version) FROM order_query.flyway_schema_history WHERE success",
            String.class);
    assertThat(version).isEqualTo("2");

    String table =
        jdbcTemplate.queryForObject(
            "SELECT to_regclass('order_query.order_read_model')", String.class);
    assertThat(table).isEqualTo("order_query.order_read_model");
  }

  @Test
  @DisplayName("liveness and readiness probes are up without the monolith")
  void livenessAndReadinessProbesAreUpWithoutTheMonolith() throws Exception {
    HttpResponse<String> liveness = get("/actuator/health/liveness");
    HttpResponse<String> readiness = get("/actuator/health/readiness");

    assertThat(liveness.statusCode()).isEqualTo(200);
    assertThat(liveness.body()).contains("\"UP\"");
    assertThat(readiness.statusCode()).isEqualTo(200);
    assertThat(readiness.body()).contains("\"UP\"");
  }

  @Test
  @DisplayName("openapi document is generated")
  void openapiDocumentIsGenerated() throws Exception {
    HttpResponse<String> response = get("/v3/api-docs");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"openapi\"");
  }

  @Test
  @DisplayName("event pipeline metrics are exposed")
  void eventPipelineMetricsAreExposed() throws Exception {
    HttpResponse<String> response = get("/actuator/prometheus");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains(
            "events_consumed_total",
            "events_dlq_total",
            "kafka_consumer_lag",
            "order_read_model_lag_seconds",
            "consumer=\"order-query\"");
  }

  @Test
  @DisplayName("sensitive actuator endpoints are not exposed")
  void sensitiveActuatorEndpointsAreNotExposed() throws Exception {
    HttpResponse<String> env = get("/actuator/env");
    HttpResponse<String> beans = get("/actuator/beans");
    HttpResponse<String> heapdump = get("/actuator/heapdump");
    HttpResponse<String> info = get("/actuator/info");

    assertThat(env.statusCode()).isEqualTo(404);
    assertThat(beans.statusCode()).isEqualTo(404);
    assertThat(heapdump.statusCode()).isEqualTo(404);
    assertThat(info.statusCode()).isEqualTo(200);
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
