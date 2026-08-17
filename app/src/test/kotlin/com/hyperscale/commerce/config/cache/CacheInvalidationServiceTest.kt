package com.hyperscale.commerce.config.cache

import com.hyperscale.commerce.contracts.CacheInvalidationEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.contains
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.kafka.core.KafkaTemplate

class CacheInvalidationServiceTest {

  @Test
  fun `handleInvalidation successfully evicts local key from registered NearCache`() {
    val service = CacheInvalidationService()
    val cache =
        NearCache<Long, TestProduct>(
            name = "catalog_products", valueClass = TestProduct::class.java)
    service.registerCache(cache)

    cache.put(100L, TestProduct(100L, "Headphones", 79.99))
    assertThat(cache.l1Cache.getIfPresent(100L)).isNotNull()

    // Trigger invalidation event
    val event =
        CacheInvalidationEvent(
            cacheName = "catalog_products", key = "100", originInstanceId = "other-pod-uuid")
    service.handleInvalidation(event)

    assertThat(cache.l1Cache.getIfPresent(100L)).isNull()
  }

  @Test
  fun `handleInvalidation with null key clears entire cache`() {
    val service = CacheInvalidationService()
    val cache =
        NearCache<Long, TestProduct>(
            name = "catalog_products", valueClass = TestProduct::class.java)
    service.registerCache(cache)

    cache.put(101L, TestProduct(101L, "Mouse", 29.99))
    cache.put(102L, TestProduct(102L, "Keyboard", 49.99))
    assertThat(cache.l1Cache.asMap()).hasSize(2)

    val event =
        CacheInvalidationEvent(
            cacheName = "catalog_products", key = null, originInstanceId = "other-pod-uuid")
    service.handleInvalidation(event)

    assertThat(cache.l1Cache.asMap()).isEmpty()
  }

  @Test
  fun `publishInvalidation writes to catalog broadcast topic`() {
    @Suppress("UNCHECKED_CAST")
    val template = mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>
    val service = CacheInvalidationService(template)

    service.publishInvalidation("catalog_products", "100")

    verify(template)
        .send(
            eq(CacheInvalidationService.TOPIC_CATALOG_CACHE_INVALIDATION),
            eq("catalog_products"),
            contains("\"cacheName\":\"catalog_products\""),
        )
  }
}
