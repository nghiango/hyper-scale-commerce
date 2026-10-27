package com.hyperscale.commerce;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ApplicationIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

  private final JdbcTemplate jdbcTemplate;
  private final int port;
  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Autowired
  public ApplicationIntegrationTest(
      JdbcTemplate jdbcTemplate, @Value("${local.server.port}") int port) {
    this.jdbcTemplate = jdbcTemplate;
    this.port = port;
  }

  @Test
  @DisplayName("connects to the isolated container database")
  void connectsToTheIsolatedContainerDatabase() {
    assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
  }

  @Test
  @DisplayName("flyway migrations are applied")
  void flywayMigrationsAreApplied() {
    String version =
        jdbcTemplate.queryForObject(
            "SELECT max(version) FROM flyway_schema_history WHERE success", String.class);
    assertThat(version).isEqualTo("7");
  }

  @Test
  @DisplayName("catalog products table can be inserted and selected")
  void catalogProductsTableCanBeInsertedAndSelected() {
    jdbcTemplate.update(
        """
        INSERT INTO catalog.products (sku, name, description, price, availability, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, now(), now())
        ON CONFLICT (sku) DO UPDATE SET
          name = EXCLUDED.name,
          description = EXCLUDED.description,
          price = EXCLUDED.price,
          availability = EXCLUDED.availability,
          updated_at = now()
        """,
        "TEST-SKU",
        "Test Product",
        "A test product",
        12345,
        "IN_STOCK");

    String name =
        jdbcTemplate.queryForObject(
            "SELECT name FROM catalog.products WHERE sku = ?", String.class, "TEST-SKU");
    assertThat(name).isEqualTo("Test Product");
  }

  @Test
  @DisplayName("liveness and readiness probes are up")
  void livenessAndReadinessProbesAreUp() throws Exception {
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
            "events_published_total",
            "events_consumed_total",
            "events_dlq_total",
            "kafka_consumer_lag",
            "hyperscale_cache_gets_total",
            "datasource_connections_active",
            "postgres_replication_lag_seconds");
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
