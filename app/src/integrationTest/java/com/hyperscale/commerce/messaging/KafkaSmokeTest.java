package com.hyperscale.commerce.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
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
public class KafkaSmokeTest {

  private static final String SMOKE_TOPIC = "smoke-topic";
  private static final String SMOKE_KEY = "smoke-key";
  private static final String SMOKE_VALUE = "smoke-value";
  private static final String SMOKE_GROUP = "smoke-group";
  private static final long TIMEOUT_SECONDS = 10L;
  private static final long POLL_MILLIS = 500L;
  private static final int HEALTH_RETRIES = 20;
  private static final long HEALTH_RETRY_DELAY_MILLIS = 500L;

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
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final com.hyperscale.commerce.config.KafkaProperties kafkaProperties;

  @Autowired
  public KafkaSmokeTest(
      @LocalServerPort int port,
      KafkaTemplate<String, String> kafkaTemplate,
      com.hyperscale.commerce.config.KafkaProperties kafkaProperties) {
    this.port = port;
    this.kafkaTemplate = kafkaTemplate;
    this.kafkaProperties = kafkaProperties;
  }

  @Test
  @DisplayName("produces and consumes a message via Kafka")
  void producesAndConsumesAMessageViaKafka() throws Exception {
    kafkaTemplate.send(SMOKE_TOPIC, SMOKE_KEY, SMOKE_VALUE).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    Properties props = new Properties();
    props.put("bootstrap.servers", kafkaProperties.bootstrapServers());
    props.put("group.id", SMOKE_GROUP);
    props.put("key.deserializer", StringDeserializer.class.getName());
    props.put("value.deserializer", StringDeserializer.class.getName());
    props.put("auto.offset.reset", "earliest");

    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(SMOKE_TOPIC));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
      String received = null;
      while (received == null && System.nanoTime() < deadline) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(POLL_MILLIS));
        for (ConsumerRecord<String, String> record : records) {
          if (SMOKE_KEY.equals(record.key())) {
            received = record.value();
            break;
          }
        }
      }
      assertThat(received).isEqualTo(SMOKE_VALUE);
    }
  }

  @Test
  @DisplayName("exposes kafka health component")
  void exposesKafkaHealthComponent() throws Exception {
    HttpClient httpClient = HttpClient.newHttpClient();
    String health = "";
    for (int i = 0; i < HEALTH_RETRIES; i++) {
      HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      health = response.body();
      if (health.contains("\"kafka\":{\"status\":\"UP\"}")) {
        continue;
      }
      Thread.sleep(HEALTH_RETRY_DELAY_MILLIS);
    }
    assertThat(health).contains("\"kafka\":{\"status\":\"UP\"}");
  }
}
