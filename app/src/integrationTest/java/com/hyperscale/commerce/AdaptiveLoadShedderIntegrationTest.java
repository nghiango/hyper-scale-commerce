package com.hyperscale.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyperscale.commerce.config.loadshedding.AdaptiveLoadShedderFilter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class AdaptiveLoadShedderIntegrationTest {

  private static final String SKU_ONE = "PERF-SKU-00001";

  @Container
  static final KafkaContainer kafka =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
  }

  private final int port;
  private final AdaptiveLoadShedderFilter loadShedder;
  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Autowired
  public AdaptiveLoadShedderIntegrationTest(
      @LocalServerPort int port, AdaptiveLoadShedderFilter loadShedder) {
    this.port = port;
    this.loadShedder = loadShedder;
  }

  @Test
  @DisplayName(
      "sheds degradable catalog traffic during overload while preserving checkout and health")
  void shedsDegradableCatalogTrafficDuringOverloadWhilePreservingCheckoutAndHealth()
      throws Exception {
    // 1. Normal state: catalog returns 200
    HttpResponse<String> normalCatalog = get("/catalog/products?page=0&size=10");
    assertThat(normalCatalog.statusCode()).isEqualTo(200);

    HttpResponse<String> normalOrder =
        post("/orders", "{\"items\":[{\"sku\":\"" + SKU_ONE + "\",\"quantity\":1}]}");
    assertThat(normalOrder.statusCode()).isEqualTo(201);

    // 2. Overloaded state
    loadShedder.setForcedOverload(true);
    try {
      HttpResponse<String> shedCatalog = get("/catalog/products?page=0&size=10");
      assertThat(shedCatalog.statusCode()).isEqualTo(429);
      assertThat(shedCatalog.headers().firstValue("Retry-After")).contains("5");
      assertThat(shedCatalog.body()).contains("temporarily shed");

      // Critical checkout path remains 100% available
      HttpResponse<String> checkoutDuringOverload =
          post("/orders", "{\"items\":[{\"sku\":\"" + SKU_ONE + "\",\"quantity\":1}]}");
      assertThat(checkoutDuringOverload.statusCode()).isEqualTo(201);

      // Actuator health remains available
      HttpResponse<String> health = get("/actuator/health/liveness");
      assertThat(health.statusCode()).isEqualTo(200);
    } finally {
      loadShedder.setForcedOverload(false);
    }

    // 3. Recovered state
    HttpResponse<String> recoveredCatalog = get("/catalog/products?page=0&size=10");
    assertThat(recoveredCatalog.statusCode()).isEqualTo(200);
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
