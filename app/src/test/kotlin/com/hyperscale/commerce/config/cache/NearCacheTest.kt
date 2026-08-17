package com.hyperscale.commerce.config.cache

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

data class TestProduct(val id: Long = 0, val name: String = "", val price: Double = 0.0)

class NearCacheTest {

  @Test
  fun `l1 cache hit returns value without calling loader or L2`() {
    val meterRegistry = SimpleMeterRegistry()
    val l2Store = InMemoryL2CacheStore()
    val cache =
        NearCache<Long, TestProduct>(
            name = "products",
            l1MaxSize = 100,
            l1Ttl = Duration.ofMinutes(1),
            meterRegistry = meterRegistry,
            l2Store = l2Store,
            valueClass = TestProduct::class.java)

    val dbLoads = AtomicInteger(0)
    val loader = {
      dbLoads.incrementAndGet()
      TestProduct(1L, "Laptop", 999.99)
    }

    // First get -> DB load + populate L1 and L2
    val res1 = cache.get(1L, loader)
    assertThat(res1?.name).isEqualTo("Laptop")
    assertThat(dbLoads.get()).isEqualTo(1)

    // Second get -> L1 hit, 0 DB loads
    val res2 = cache.get(1L, loader)
    assertThat(res2?.name).isEqualTo("Laptop")
    assertThat(dbLoads.get()).isEqualTo(1)

    // Verify metrics
    val l1Hits =
        meterRegistry
            .find("hyperscale.cache.gets")
            .tag("level", "L1")
            .tag("result", "hit")
            .counter()
    assertThat(l1Hits?.count()).isEqualTo(1.0)
  }

  @Test
  fun `l1 miss with L2 hit populates L1 without calling loader`() {
    val l2Store = InMemoryL2CacheStore()
    val cache1 =
        NearCache<Long, TestProduct>(
            name = "products", l2Store = l2Store, valueClass = TestProduct::class.java)

    // Pod 1 puts product in cache (L1 + L2)
    cache1.put(2L, TestProduct(2L, "Phone", 499.99))

    // Pod 2 (fresh L1) reads from cache
    val cache2 =
        NearCache<Long, TestProduct>(
            name = "products", l2Store = l2Store, valueClass = TestProduct::class.java)

    val dbLoads = AtomicInteger(0)
    val res =
        cache2.get(2L) {
          dbLoads.incrementAndGet()
          TestProduct(2L, "Phone from DB", 499.99)
        }

    assertThat(res?.name).isEqualTo("Phone")
    assertThat(dbLoads.get()).isEqualTo(0) // Zero DB loads because L2 had the value!

    // Subsequent read from Pod 2 hits L1
    val resL1 =
        cache2.get(2L) {
          dbLoads.incrementAndGet()
          null
        }
    assertThat(resL1?.name).isEqualTo("Phone")
    assertThat(dbLoads.get()).isEqualTo(0)
  }

  @Test
  fun `fail-open resilience falls back to loader if L2 store throws exception`() {
    val failingL2 =
        object : L2CacheStore {
          override fun get(key: String): String? = throw IOException("Redis connection refused")

          override fun put(key: String, value: String, ttl: Duration) =
              throw IOException("Redis timeout")

          override fun delete(key: String) = throw IOException("Redis timeout")

          override fun deleteByPrefix(prefix: String) = throw IOException("Redis timeout")
        }

    val cache =
        NearCache<Long, TestProduct>(
            name = "products", l2Store = failingL2, valueClass = TestProduct::class.java)

    val dbLoads = AtomicInteger(0)
    val res =
        cache.get(3L) {
          dbLoads.incrementAndGet()
          TestProduct(3L, "Tablet", 299.99)
        }

    assertThat(res?.name).isEqualTo("Tablet")
    assertThat(dbLoads.get()).isEqualTo(1)
  }

  @Test
  fun `evict removes item from both L1 and L2`() {
    val meterRegistry = SimpleMeterRegistry()
    val l2Store = InMemoryL2CacheStore()
    val cache =
        NearCache<Long, TestProduct>(
            name = "products",
            meterRegistry = meterRegistry,
            l2Store = l2Store,
            valueClass = TestProduct::class.java)

    cache.put(4L, TestProduct(4L, "Monitor", 199.99))
    assertThat(l2Store.get("products:4")).isNotNull()

    cache.evict(4L)
    assertThat(cache.l1Cache.getIfPresent(4L)).isNull()
    assertThat(l2Store.get("products:4")).isNull()
    assertThat(
            meterRegistry
                .find("hyperscale.cache.evictions")
                .tag("reason", "mutation")
                .counter()
                ?.count())
        .isEqualTo(1.0)

    cache.evictLocal(4L)
    assertThat(
            meterRegistry
                .find("hyperscale.cache.evictions")
                .tag("reason", "event")
                .counter()
                ?.count())
        .isEqualTo(1.0)
  }
}
