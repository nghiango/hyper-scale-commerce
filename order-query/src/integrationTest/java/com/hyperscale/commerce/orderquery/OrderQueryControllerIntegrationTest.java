package com.hyperscale.commerce.orderquery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperscale.commerce.orderquery.testsupport.IsolatedRedisContainer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
class OrderQueryControllerIntegrationTest {

  private static final String SKU_ONE = "PERF-SKU-00001";
  private static final String SKU_TWO = "PERF-SKU-00002";
  private static final long UNKNOWN_ORDER_ID = 999999L;
  private static final long TIMEOUT_SECONDS = 15L;
  private static final long POLL_MILLIS = 500L;

  @Container
  static final KafkaContainer kafka =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @Container
  static final IsolatedRedisContainer redis =
      new IsolatedRedisContainer(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
  }

  private final int port;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final String topic;

  @Autowired
  OrderQueryControllerIntegrationTest(
      @LocalServerPort int port,
      KafkaTemplate<String, String> kafkaTemplate,
      @Value("${app.outbox.topic}") String topic) {
    this.port = port;
    this.kafkaTemplate = kafkaTemplate;
    this.topic = topic;
  }

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("gets an order by id from the read model")
  void getsAnOrderByIdFromTheReadModel() throws Exception {
    long orderId = 1001L;
    publishOrderPlaced(orderId, SKU_TWO, 1);

    HttpResponse<String> response = awaitOrder(orderId);
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = objectMapper.readTree(response.body());
    assertThat(body.get("id").asLong()).isEqualTo(orderId);
    assertThat(body.get("status").asText()).isEqualTo("PLACED");
    assertThat(body.get("items").get(0).get("sku").asText()).isEqualTo(SKU_TWO);
  }

  @Test
  @DisplayName("lists orders with pagination from the read model")
  void listsOrdersWithPaginationFromTheReadModel() throws Exception {
    long firstId = 2001L;
    long secondId = 2002L;
    publishOrderPlaced(firstId, SKU_ONE, 1);
    publishOrderPlaced(secondId, SKU_TWO, 1);

    awaitOrder(firstId);
    awaitOrder(secondId);

    HttpResponse<String> response = get("/orders?page=0&size=1");
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = objectMapper.readTree(response.body());
    assertThat(body.get("total").asLong()).isGreaterThanOrEqualTo(2);
    assertThat(body.get("items")).hasSize(1);
  }

  @Test
  @DisplayName("returns 404 for unknown order")
  void returns404ForUnknownOrder() throws Exception {
    HttpResponse<String> response = get("/orders/" + UNKNOWN_ORDER_ID);
    assertThat(response.statusCode()).isEqualTo(404);
  }

  private void publishOrderPlaced(long orderId, String sku, int quantity) throws Exception {
    String payload =
        "{\"version\":1,\"eventId\":\"event-"
            + orderId
            + "\",\"orderId\":"
            + orderId
            + ",\"status\":\"PLACED\",\"createdAt\":\"2026-08-14T00:00:00Z\",\"items\":[{\"sku\":\""
            + sku
            + "\",\"quantity\":"
            + quantity
            + "}]}";
    kafkaTemplate
        .send(topic, Long.toString(orderId), payload)
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
