package com.hyperscale.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationIntegrationTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Value("${local.server.port}")
  private int port;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  void connectsToTheIsolatedContainerDatabase() {
    assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
  }

  @Test
  void flywayBaselineMigrationIsApplied() {
    String version = jdbcTemplate.queryForObject("SELECT max(version) FROM flyway_schema_history WHERE success",
        String.class);
    assertThat(version).isEqualTo("1");
  }

  @Test
  void livenessAndReadinessProbesAreUp() throws Exception {
    HttpResponse<String> liveness = get("/actuator/health/liveness");
    HttpResponse<String> readiness = get("/actuator/health/readiness");

    assertThat(liveness.statusCode()).isEqualTo(200);
    assertThat(liveness.body()).contains("\"UP\"");
    assertThat(readiness.statusCode()).isEqualTo(200);
    assertThat(readiness.body()).contains("\"UP\"");
  }

  @Test
  void openapiDocumentIsGenerated() throws Exception {
    HttpResponse<String> response = get("/v3/api-docs");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"openapi\"");
  }

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
