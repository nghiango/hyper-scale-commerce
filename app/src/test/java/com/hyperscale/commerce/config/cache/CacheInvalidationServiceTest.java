package com.hyperscale.commerce.config.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hyperscale.commerce.contracts.CacheInvalidationEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class CacheInvalidationServiceTest {

  @Test
  @DisplayName("handleInvalidation successfully evicts local key from registered NearCache")
  void handleInvalidationSuccessfullyEvictsLocalKeyFromRegisteredNearCache() {
    CacheInvalidationService service = new CacheInvalidationService();
    NearCache<Long, TestProduct> cache = new NearCache<>("catalog_products", TestProduct.class);
    service.registerCache(cache);

    cache.put(100L, new TestProduct(100L, "Headphones", 79.99));
    assertThat(cache.getL1Cache().getIfPresent(100L)).isNotNull();

    // Trigger invalidation event
    CacheInvalidationEvent event =
        new CacheInvalidationEvent("catalog_products", "100", "other-pod-uuid");
    service.handleInvalidation(event);

    assertThat(cache.getL1Cache().getIfPresent(100L)).isNull();
  }

  @Test
  @DisplayName("handleInvalidation with null key clears entire cache")
  void handleInvalidationWithNullKeyClearsEntireCache() {
    CacheInvalidationService service = new CacheInvalidationService();
    NearCache<Long, TestProduct> cache = new NearCache<>("catalog_products", TestProduct.class);
    service.registerCache(cache);

    cache.put(101L, new TestProduct(101L, "Mouse", 29.99));
    cache.put(102L, new TestProduct(102L, "Keyboard", 49.99));
    assertThat(cache.getL1Cache().asMap()).hasSize(2);

    CacheInvalidationEvent event =
        new CacheInvalidationEvent("catalog_products", null, "other-pod-uuid");
    service.handleInvalidation(event);

    assertThat(cache.getL1Cache().asMap()).isEmpty();
  }

  @Test
  @DisplayName("publishInvalidation writes to catalog broadcast topic")
  void publishInvalidationWritesToCatalogBroadcastTopic() {
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    CacheInvalidationService service = new CacheInvalidationService(template);

    service.publishInvalidation("catalog_products", "100");

    verify(template)
        .send(
            eq(CacheInvalidationService.TOPIC_CATALOG_CACHE_INVALIDATION),
            eq("catalog_products"),
            contains("\"cacheName\":\"catalog_products\""));
  }
}
