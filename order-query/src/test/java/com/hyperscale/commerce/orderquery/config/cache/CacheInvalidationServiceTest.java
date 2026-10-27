package com.hyperscale.commerce.orderquery.config.cache;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hyperscale.commerce.contracts.CacheInvalidationEvent;
import com.hyperscale.commerce.orderquery.application.OrderQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class CacheInvalidationServiceTest {
  private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

  @Test
  @DisplayName("listener evicts the addressed local order cache")
  void listenerEvictsTheAddressedLocalOrderCache() {
    OrderQueryService orderQueryService = mock(OrderQueryService.class);
    CacheInvalidationListener listener =
        new CacheInvalidationListener(objectMapper, orderQueryService);
    String payload =
        objectMapper.writeValueAsString(
            new CacheInvalidationEvent(OrderQueryService.ORDER_CACHE_NAME, "42", "another-pod"));

    listener.onInvalidation(payload);

    verify(orderQueryService).evictLocal(42L);
  }

  @Test
  @DisplayName("publisher writes versioned invalidation event to broadcast topic")
  void publisherWritesVersionedInvalidationEventToBroadcastTopic() {
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    KafkaCacheInvalidationPublisher publisher =
        new KafkaCacheInvalidationPublisher(template, objectMapper);

    publisher.publish(OrderQueryService.ORDER_CACHE_NAME, "42");

    verify(template)
        .send(
            eq(CacheInvalidationListener.TOPIC_ORDER_CACHE_INVALIDATION),
            eq("42"),
            contains("\"cacheName\":\"order_query\""));
  }
}
