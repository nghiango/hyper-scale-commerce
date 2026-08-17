package com.hyperscale.commerce.orderquery.config.cache

import com.hyperscale.commerce.contracts.CacheInvalidationEvent
import com.hyperscale.commerce.orderquery.application.OrderQueryService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.kafka.core.KafkaTemplate
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

class CacheInvalidationServiceTest {
  private val objectMapper: ObjectMapper = JsonMapper.builder().findAndAddModules().build()

  @Test
  fun `listener evicts the addressed local order cache`() {
    val orderQueryService = mock(OrderQueryService::class.java)
    val listener = CacheInvalidationListener(objectMapper, orderQueryService)
    val payload =
        objectMapper.writeValueAsString(
            CacheInvalidationEvent(
                cacheName = OrderQueryService.ORDER_CACHE_NAME,
                key = "42",
                originInstanceId = "another-pod",
            ))

    listener.onInvalidation(payload)

    verify(orderQueryService).evictLocal(42L)
  }

  @Test
  fun `publisher writes versioned invalidation event to broadcast topic`() {
    @Suppress("UNCHECKED_CAST")
    val template = mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>
    val publisher = KafkaCacheInvalidationPublisher(template, objectMapper)

    publisher.publish(OrderQueryService.ORDER_CACHE_NAME, "42")

    verify(template)
        .send(
            org.mockito.ArgumentMatchers.eq(
                CacheInvalidationListener.TOPIC_ORDER_CACHE_INVALIDATION),
            org.mockito.ArgumentMatchers.eq("42"),
            org.mockito.ArgumentMatchers.contains("\"cacheName\":\"order_query\""),
        )
  }
}
