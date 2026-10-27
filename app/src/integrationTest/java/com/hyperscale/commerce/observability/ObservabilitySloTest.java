package com.hyperscale.commerce.observability;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ObservabilitySloTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.listener.auto-startup", () -> "false");
  }

  private final int port;
  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Autowired
  public ObservabilitySloTest(@LocalServerPort int port) {
    this.port = port;
  }

  @Test
  @DisplayName("POST orders p95 and success rate are observable in prometheus")
  void postOrdersP95AndSuccessRateAreObservableInPrometheus() throws Exception {
    HttpResponse<String> success =
        post("{\"items\":[{\"sku\":\"PERF-SKU-00001\",\"quantity\":1}]}");
    assertThat(success.statusCode()).isEqualTo(201);

    HttpResponse<String> failure = post("{\"items\":[]}");
    assertThat(failure.statusCode()).isBetween(400, 499);

    String prometheus = get("/actuator/prometheus").body();
    assertThat(prometheus).contains("slo_post_orders_success_rate");
    assertThat(prometheus).contains("slo_get_order_by_id_p95");
    assertThat(prometheus).contains("slo_get_orders_p95");
    assertThat(prometheus).contains("http_server_requests_seconds");
    assertThat(prometheus).contains("method=\"");
    assertThat(prometheus).contains("POST");
    assertThat(prometheus).contains("0.95");
  }

  private HttpResponse<String> post(String body) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
