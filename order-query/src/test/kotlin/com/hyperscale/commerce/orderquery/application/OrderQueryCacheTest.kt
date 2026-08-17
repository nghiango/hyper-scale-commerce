package com.hyperscale.commerce.orderquery.application

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
    val service = OrderQueryService(dsl, objectMapper)

    // Verify eviction methods execute safely
    service.evictOrder(12345L)
    service.evictAll()

    assertThat(service).isNotNull
  }
}
