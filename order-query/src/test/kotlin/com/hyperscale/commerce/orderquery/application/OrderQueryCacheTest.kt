package com.hyperscale.commerce.orderquery.application

import com.hyperscale.commerce.orderquery.application.cache.L2CacheStore
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import tools.jackson.databind.ObjectMapper

class OrderQueryCacheTest {

  @Test
  fun `evictOrder and evictAll function cleanly without exceptions`() {
    val dsl = mock(DSLContext::class.java)
    val objectMapper = mock(ObjectMapper::class.java)
    val service =
        OrderQueryService(dsl, objectMapper, l2CacheStore = mock(L2CacheStore::class.java))

    // Verify eviction methods execute safely
    service.evictOrder(12345L)
    service.evictAll()

    assertThat(service).isNotNull
  }
}
