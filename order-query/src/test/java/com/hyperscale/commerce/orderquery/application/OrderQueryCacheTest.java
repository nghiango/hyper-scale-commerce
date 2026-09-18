package com.hyperscale.commerce.orderquery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hyperscale.commerce.orderquery.application.cache.L2CacheStore;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OrderQueryCacheTest {

  @Test
  @DisplayName("evictOrder and evictAll function cleanly without exceptions")
  void evictOrderAndEvictAllFunctionCleanlyWithoutExceptions() {
    DSLContext dsl = mock(DSLContext.class);
    ObjectMapper objectMapper = mock(ObjectMapper.class);
    OrderQueryService service = new OrderQueryService(dsl, objectMapper, mock(L2CacheStore.class));

    // Verify eviction methods execute safely
    service.evictOrder(12345L);
    service.evictAll();

    assertThat(service).isNotNull();
  }
}
