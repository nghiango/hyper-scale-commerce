package com.hyperscale.commerce.orderquery.admin;

import static com.hyperscale.commerce.orderquery.jooq.order_query.Tables.ORDER_READ_MODEL;
import static org.assertj.core.api.Assertions.assertThat;

import com.hyperscale.commerce.orderquery.application.DlqReplayResult;
import com.hyperscale.commerce.orderquery.application.DlqReplayService;
import com.hyperscale.commerce.orderquery.application.OrderDto;
import com.hyperscale.commerce.orderquery.application.OrderQueryService;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DlqReplayIntegrationTest {

  @Container
  static final KafkaContainer kafka =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
  }

  private final DlqReplayService dlqReplayService;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final OrderQueryService orderQueryService;
  private final DSLContext dsl;

  @Autowired
  DlqReplayIntegrationTest(
      DlqReplayService dlqReplayService,
      KafkaTemplate<String, String> kafkaTemplate,
      OrderQueryService orderQueryService,
      DSLContext dsl) {
    this.dlqReplayService = dlqReplayService;
    this.kafkaTemplate = kafkaTemplate;
    this.orderQueryService = orderQueryService;
    this.dsl = dsl;
  }

  @BeforeEach
  void clean() {
    dsl.deleteFrom(ORDER_READ_MODEL).execute();
    orderQueryService.evictAll();
  }

  @Test
  @DisplayName(
      "replays dead-lettered event to target topic and projection processes it successfully")
  void replaysDeadLetteredEventToTargetTopicAndProjectionProcessesItSuccessfully()
      throws Exception {
    long orderId = 77777L;
    String dlqTopic = "order-placed-dlq";
    String targetTopic = "order-placed";

    String validPayload =
        """
        {
          "version": 1,
          "eventId": "evt-dlq-77777",
          "orderId": %d,
          "status": "PLACED",
          "createdAt": "%s",
          "items": [{"sku": "SKU-REPLAY-1", "quantity": 3}],
          "aggregateVersion": 1
        }
        """
            .formatted(orderId, Instant.now());

    // 1. Send event to DLQ topic
    kafkaTemplate
        .send(new ProducerRecord<>(dlqTopic, Long.toString(orderId), validPayload))
        .get(5, TimeUnit.SECONDS);

    // 2. Trigger DLQ Replay
    DlqReplayResult result = dlqReplayService.replay(dlqTopic, targetTopic, 10);
    assertThat(result.replayedCount()).isGreaterThanOrEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);

    // 3. Await projection consumption
    awaitProjection(orderId);

    OrderDto order = orderQueryService.getOrder(orderId);
    assertThat(order.status()).isEqualTo("PLACED");
    assertThat(order.items()).hasSize(1);
    assertThat(order.items().get(0)).extracting("sku").isEqualTo("SKU-REPLAY-1");
    assertThat(order.items().get(0)).extracting("quantity").isEqualTo(3);
  }

  private void awaitProjection(long orderId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10000;
    while (System.currentTimeMillis() < deadline) {
      Long exists =
          dsl.selectCount()
              .from(ORDER_READ_MODEL)
              .where(ORDER_READ_MODEL.ORDER_ID.eq(orderId))
              .fetchOne(0, Long.class);
      if (exists != null && exists > 0) {
        return;
      }
      Thread.sleep(200);
    }
    throw new AssertionError(
        "Order " + orderId + " was not projected after DLQ replay within timeout");
  }
}
