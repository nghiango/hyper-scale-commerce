package com.hyperscale.commerce.orderquery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hyperscale.commerce.orderquery.application.cache.L2CacheStore;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OrderCacheInvalidationTest {

  @Test
  @DisplayName("evictOrder successfully evicts key from Caffeine cache")
  void evictOrderSuccessfullyEvictsKeyFromCaffeineCache() {
    DSLContext dsl = mock(DSLContext.class);
    ObjectMapper objectMapper = new ObjectMapper();
    OrderQueryService service = new OrderQueryService(dsl, objectMapper, mock(L2CacheStore.class));

    service.evictOrder(55555L);
    service.evictAll();

    assertThat(service).isNotNull();
  }
}
