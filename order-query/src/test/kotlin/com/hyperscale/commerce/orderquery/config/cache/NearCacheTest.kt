package com.hyperscale.commerce.orderquery.config.cache

import com.hyperscale.commerce.orderquery.application.cache.L2CacheStore
import com.hyperscale.commerce.orderquery.application.cache.NearCache
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class NearCacheTest {
  private data class CacheValue(val id: Long = 0, val name: String = "")

  @Test
  fun `shared L2 value warms a second pod cache without invoking its loader`() {
    val store = MemoryStore()
    val first = cache(store)
    val second = cache(store)

    assertThat(first.get(1L) { CacheValue(1L, "shared") }).isEqualTo(CacheValue(1L, "shared"))
    var loads = 0
    val fromSecond =
        second.get(1L) {
          loads++
          CacheValue(1L, "database")
        }

    assertThat(fromSecond).isEqualTo(CacheValue(1L, "shared"))
    assertThat(loads).isZero()
  }

  @Test
  fun `L2 failure fails open to loader`() {
    val failingStore =
        object : L2CacheStore {
          override fun get(key: String): String? = error("redis unavailable")

          override fun put(key: String, value: String, ttl: Duration) = error("redis unavailable")

          override fun delete(key: String) = error("redis unavailable")

          override fun deleteByPrefix(prefix: String) = error("redis unavailable")
        }

    assertThat(cache(failingStore).get(2L) { CacheValue(2L, "database") })
        .isEqualTo(CacheValue(2L, "database"))
  }

  @Test
  fun `invalidation waits for a blocked load and removes its result`() {
    val store = MemoryStore()
    val cache = cache(store)
    val source = AtomicReference(CacheValue(3L, "stale"))
    val firstLoadStarted = CountDownLatch(1)
    val allowFirstLoadToFinish = CountDownLatch(1)
    val loads = AtomicInteger()
    val evictionStarted = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)

    try {
      val result =
          executor.submit<CacheValue?> {
            cache.get(3L) {
              val snapshot = source.get()
              if (loads.incrementAndGet() == 1) {
                firstLoadStarted.countDown()
                check(allowFirstLoadToFinish.await(5, TimeUnit.SECONDS))
              }
              snapshot
            }
          }

      assertThat(firstLoadStarted.await(5, TimeUnit.SECONDS)).isTrue()
      val eviction =
          executor.submit {
            evictionStarted.countDown()
            cache.evictLocal(3L)
          }
      assertThat(evictionStarted.await(5, TimeUnit.SECONDS)).isTrue()
      source.set(CacheValue(3L, "fresh"))
      allowFirstLoadToFinish.countDown()

      assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(CacheValue(3L, "stale"))
      eviction.get(5, TimeUnit.SECONDS)
      assertThat(
              cache.get(3L) {
                loads.incrementAndGet()
                source.get()
              })
          .isEqualTo(CacheValue(3L, "fresh"))
      assertThat(loads.get()).isEqualTo(2)
    } finally {
      allowFirstLoadToFinish.countDown()
      executor.shutdownNow()
    }
  }

  private fun cache(store: L2CacheStore): NearCache<Long, CacheValue> =
      NearCache(
          name = "orders",
          l1MaxSize = 100,
          l1Ttl = Duration.ofMinutes(1),
          l2Ttl = Duration.ofMinutes(10),
          l2Store = store,
          valueClass = CacheValue::class.java,
          objectMapper = ObjectMapper(),
      )

  private class MemoryStore : L2CacheStore {
    private val values = ConcurrentHashMap<String, String>()

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String, ttl: Duration) {
      values[key] = value
    }

    override fun delete(key: String) {
      values.remove(key)
    }

    override fun deleteByPrefix(prefix: String) {
      values.keys.removeIf { it.startsWith(prefix) }
    }
  }
}
