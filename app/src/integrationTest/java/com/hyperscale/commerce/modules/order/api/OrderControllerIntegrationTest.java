package com.hyperscale.commerce.modules.order.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
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
public class OrderControllerIntegrationTest {

  private static final String ORDER_PLACED_EVENT_TYPE = "OrderPlaced";
  private static final String SKU_ONE = "PERF-SKU-00001";
  private static final String SKU_TWO = "PERF-SKU-00002";

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
  private final JdbcTemplate jdbcTemplate;
  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Autowired
  public OrderControllerIntegrationTest(@LocalServerPort int port, JdbcTemplate jdbcTemplate) {
    this.port = port;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Test
  @DisplayName("creates an order and writes one outbox event")
  void createsAnOrderAndWritesOneOutboxEvent() throws Exception {
    HttpResponse<String> response =
        post("/orders", "{\"items\":[{\"sku\":\"" + SKU_ONE + "\",\"quantity\":2}]}");

    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode body = objectMapper.readTree(response.body());
    long orderId = body.get("id").asLong();
    assertThat(body.get("status").asText()).isEqualTo("PLACED");
    assertThat(body.get("items")).hasSize(1);
    assertThat(body.get("items").get(0).get("sku").asText()).isEqualTo(SKU_ONE);
    assertThat(body.get("items").get(0).get("quantity").asInt()).isEqualTo(2);

    Long outboxCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM \"order\".outbox_events WHERE event_type = ? AND aggregate_id = ?",
            Long.class,
            ORDER_PLACED_EVENT_TYPE,
            String.valueOf(orderId));
    assertThat(outboxCount).isEqualTo(1);
  }

  @Test
  @DisplayName("rejects invalid order items")
  void rejectsInvalidOrderItems() throws Exception {
    HttpResponse<String> response =
        post("/orders", "{\"items\":[{\"sku\":\"" + SKU_ONE + "\",\"quantity\":0}]}");
    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  @DisplayName("idempotency key deduplicates client retries and returns cached 201")
  void idempotencyKeyDeduplicatesClientRetriesAndReturnsCached201() throws Exception {
    String idempotencyKey = UUID.randomUUID().toString();
    String payload = "{\"items\":[{\"sku\":\"" + SKU_ONE + "\",\"quantity\":5}]}";

    HttpResponse<String> response1 = postWithKey("/orders", payload, idempotencyKey);
    assertThat(response1.statusCode()).isEqualTo(201);
    JsonNode body1 = objectMapper.readTree(response1.body());
    long orderId1 = body1.get("id").asLong();

    HttpResponse<String> response2 = postWithKey("/orders", payload, idempotencyKey);
    assertThat(response2.statusCode()).isEqualTo(201);
    JsonNode body2 = objectMapper.readTree(response2.body());
    long orderId2 = body2.get("id").asLong();

    assertThat(orderId1).isEqualTo(orderId2);

    Long keyRecordCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM \"order\".idempotency_keys WHERE key = ? AND status = 'COMPLETED'",
            Long.class,
            idempotencyKey);
    assertThat(keyRecordCount).isEqualTo(1);
  }

  @Test
  @DisplayName("idempotency key with differing payload returns 422 unprocessable entity")
  void idempotencyKeyWithDifferingPayloadReturns422UnprocessableEntity() throws Exception {
    String idempotencyKey = UUID.randomUUID().toString();
    String payload1 = "{\"items\":[{\"sku\":\"" + SKU_ONE + "\",\"quantity\":1}]}";
    String payload2 = "{\"items\":[{\"sku\":\"" + SKU_TWO + "\",\"quantity\":9}]}";

    HttpResponse<String> response1 = postWithKey("/orders", payload1, idempotencyKey);
    assertThat(response1.statusCode()).isEqualTo(201);

    HttpResponse<String> response2 = postWithKey("/orders", payload2, idempotencyKey);
    assertThat(response2.statusCode()).isEqualTo(422);
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

  private HttpResponse<String> postWithKey(String path, String body, String key)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
