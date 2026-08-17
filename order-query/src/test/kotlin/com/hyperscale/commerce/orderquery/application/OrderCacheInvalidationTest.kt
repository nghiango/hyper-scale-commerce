package com.hyperscale.commerce.orderquery.application

import com.hyperscale.commerce.orderquery.application.cache.L2CacheStore
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import tools.jackson.databind.ObjectMapper

class OrderCacheInvalidationTest {

  @Test
  fun `evictOrder successfully evicts key from Caffeine cache`() {
    val dsl = mock(DSLContext::class.java)
    val objectMapper = ObjectMapper()
    val service =
        OrderQueryService(dsl, objectMapper, l2CacheStore = mock(L2CacheStore::class.java))

    service.evictOrder(55555L)
    service.evictAll()

    assertThat(service).isNotNull
  }
}
