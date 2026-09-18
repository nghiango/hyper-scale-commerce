package com.hyperscale.commerce.orderquery.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilitySloTest {

  private static final String SKU = "PERF-SKU-00001";
  private static final long TIMEOUT_SECONDS = 15L;
  private static final long POLL_MILLIS = 500L;

  @Container
  static final KafkaContainer kafka =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
  }

  private final int port;
  private final KafkaTemplate<String, String> kafkaTemplate;

  @Autowired
  ObservabilitySloTest(@LocalServerPort int port, KafkaTemplate<String, String> kafkaTemplate) {
    this.port = port;
    this.kafkaTemplate = kafkaTemplate;
  }

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  @DisplayName("GET order p95 is observable in prometheus")
  void getOrderP95IsObservableInPrometheus() throws Exception {
    long orderId = 3001L;
    publishOrderPlaced(orderId);

    awaitOrder(orderId);

    HttpResponse<String> listResponse = get("/orders?page=0&size=10");
    assertThat(listResponse.statusCode()).isEqualTo(200);

    String prometheus = get("/actuator/prometheus").body();
    assertThat(prometheus).contains("slo_get_order_by_id_p95");
    assertThat(prometheus).contains("slo_get_orders_p95");
    assertThat(prometheus).contains("http_server_requests_seconds");
    assertThat(prometheus).contains("method=");
    assertThat(prometheus).contains("GET");
    assertThat(prometheus).contains("0.95");
  }

  private void publishOrderPlaced(long orderId) throws Exception {
    String payload =
        "{\"version\":1,\"eventId\":\"event-"
            + orderId
            + "\",\"orderId\":"
            + orderId
            + ",\"status\":\"PLACED\",\"createdAt\":\"2026-08-14T00:00:00Z\",\"items\":[{\"sku\":\""
            + SKU
            + "\",\"quantity\":1}]}";
    kafkaTemplate
        .send("order-placed", Long.toString(orderId), payload)
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> awaitOrder(long orderId) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      HttpResponse<String> response = get("/orders/" + orderId);
      if (response.statusCode() == 200) {
        return response;
      }
      Thread.sleep(POLL_MILLIS);
    }
    return get("/orders/" + orderId);
  }
}
