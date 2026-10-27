package com.hyperscale.commerce.orderquery.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import com.hyperscale.commerce.orderquery.OrderQueryApplication;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PostgresOutageIntegrationTest {

  private static final String SKU = "PERF-SKU-00001";
  private static final long TIMEOUT_SECONDS = 30L;
  private static final long POLL_MILLIS = 500L;
  private static final String ORDER_PLACED_TOPIC = "order-placed";
  private static final int KAFKA_HOST_PORT = 29095;
  private static final int KAFKA_CONTAINER_PORT = 9093;
  private static final int POSTGRES_HOST_PORT = 5435;
  private static final int POSTGRES_CONTAINER_PORT = 5432;

  private static final String postgresVolume = "hyperscale-outage-" + java.util.UUID.randomUUID();

  @Container static final KafkaContainer kafka = createKafka();

  @Container static final PostgreSQLContainer<?> postgres = createPostgres();

  @AfterAll
  static void removePostgresVolume() {
    postgres.stop();
    try {
      DockerClientFactory.instance().client().removeVolumeCmd(postgresVolume).exec();
    } catch (NotFoundException ignored) {
      // Startup may fail before Docker creates the volume.
    }
  }

  private static KafkaContainer createKafka() {
    KafkaContainer container =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));
    container.setPortBindings(List.of(KAFKA_HOST_PORT + ":" + KAFKA_CONTAINER_PORT));
    return container;
  }

  private static PostgreSQLContainer<?> createPostgres() {
    PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16");
    container.setPortBindings(List.of(POSTGRES_HOST_PORT + ":" + POSTGRES_CONTAINER_PORT));
    container.withCreateContainerCmdModifier(
        command ->
            command
                .getHostConfig()
                .withBinds(new Bind(postgresVolume, new Volume("/var/lib/postgresql/data"))));
    container.waitingFor(
        Wait.forLogMessage(".*database system is ready to accept connections.*", 2));
    return container;
  }

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  @DisplayName("order-query survives a postgres outage and catches up")
  void orderQuerySurvivesAPostgresOutageAndCatchesUp() throws Exception {
    ConfigurableApplicationContext context = startOrderQuery();
    int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));

    try {
      ResilienceHarness.awaitReadiness(port);
      String producerBootstrap = kafka.getBootstrapServers();

      long order1 = 1L;
      produceOrderPlaced(order1, producerBootstrap);
      awaitOrder(port, order1);
      assertThat(get(port, "/orders/" + order1).statusCode()).isEqualTo(200);

      ResilienceHarness.stopPostgres(postgres);
      awaitAggregateHealth(port, "DOWN");
      ResilienceHarness.awaitReadiness(port);
      long uncachedOrder = 99999L;
      assertThat(get(port, "/orders/" + uncachedOrder).statusCode()).isGreaterThanOrEqualTo(500);

      long order2 = 2L;
      produceOrderPlaced(order2, producerBootstrap);

      ResilienceHarness.startPostgres(postgres);
      awaitAggregateHealth(port, "UP");
      awaitOrder(port, order1);
      assertThat(get(port, "/orders/" + order1).statusCode()).isEqualTo(200);
      awaitOrder(port, order2);
      assertThat(get(port, "/orders/" + order2).statusCode()).isEqualTo(200);
    } finally {
      context.close();
    }
  }

  private ConfigurableApplicationContext startOrderQuery() {
    return new SpringApplicationBuilder(OrderQueryApplication.class)
        .run(
            "--spring.config.name=orderquery",
            "--spring.application.name=order-query",
            "--app.name=order-query",
            "--spring.datasource.url=" + postgres.getJdbcUrl(),
            "--spring.datasource.username=" + postgres.getUsername(),
            "--spring.datasource.password=" + postgres.getPassword(),
            "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
            "--spring.flyway.locations=classpath:db/migration-order-query",
            "--spring.flyway.schemas=order_query",
            "--management.health.redis.enabled=false",
            "--server.port=0");
  }

  private void produceOrderPlaced(long orderId, String bootstrapServers) throws Exception {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
      String event =
          "{\"version\":1,\"eventId\":\"p6-04-"
              + orderId
              + "\",\"orderId\":"
              + orderId
              + ",\"status\":\"PLACED\",\"createdAt\":\""
              + Instant.now()
              + "\",\"items\":[{\"sku\":\""
              + SKU
              + "\",\"quantity\":2}]}";
      producer.send(new ProducerRecord<>(ORDER_PLACED_TOPIC, Long.toString(orderId), event)).get();
    }
  }

  private void awaitOrder(int port, long orderId) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      if (get(port, "/orders/" + orderId).statusCode() == 200) {
        return;
      }
      Thread.sleep(POLL_MILLIS);
    }
    throw new AssertionError(
        "Order " + orderId + " not projected within " + TIMEOUT_SECONDS + " seconds");
  }

  private void awaitAggregateHealth(int port, String expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    String lastBody = "";
    while (System.nanoTime() < deadline) {
      HttpResponse<String> response = get(port, "/actuator/health");
      lastBody = response.body();
      var health = new com.fasterxml.jackson.databind.ObjectMapper().readTree(lastBody);
      if (expected.equals(health.path("status").asText())
          && expected.equals(health.path("components").path("db").path("status").asText())
          && response.statusCode() == ("UP".equals(expected) ? 200 : 503)) {
        return;
      }
      Thread.sleep(POLL_MILLIS);
    }
    throw new AssertionError(
        "Aggregate/database health did not become " + expected + ": " + lastBody);
  }

  private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
