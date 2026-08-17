@file:Suppress("LongParameterList", "MagicNumber", "TooGenericExceptionCaught")

package com.hyperscale.commerce.config.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

class NearCache<K : Any, V : Any>(
    val name: String,
    val l1MaxSize: Long = 10_000L,
    val l1Ttl: Duration = Duration.ofSeconds(60),
    val l2Ttl: Duration = Duration.ofMinutes(10),
    private val meterRegistry: MeterRegistry? = null,
    private val objectMapper: ObjectMapper = ObjectMapper().findAndRegisterModules(),
    private val l2Store: L2CacheStore? = null,
    private val valueClass: Class<V>? = null
) {
  private val log = LoggerFactory.getLogger(NearCache::class.java)

  val l1Cache: Cache<K, V> =
      Caffeine.newBuilder()
          .maximumSize(l1MaxSize)
          .expireAfterWrite(l1Ttl)
          .removalListener<K, V> { _, _, cause ->
            when (cause) {
              RemovalCause.EXPIRED -> evictionCounter("ttl")?.increment()
              RemovalCause.SIZE -> evictionCounter("lru")?.increment()
              else -> Unit
            }
          }
          .build()

  private val l1Hits: Counter? =
      meterRegistry?.counter("hyperscale.cache.gets", "cache", name, "level", "L1", "result", "hit")
  private val l1Misses: Counter? =
      meterRegistry?.counter(
          "hyperscale.cache.gets", "cache", name, "level", "L1", "result", "miss")
  private val l2Hits: Counter? =
      meterRegistry?.counter("hyperscale.cache.gets", "cache", name, "level", "L2", "result", "hit")
  private val l2Misses: Counter? =
      meterRegistry?.counter(
          "hyperscale.cache.gets", "cache", name, "level", "L2", "result", "miss")
  private val evictionCounters = ConcurrentHashMap<String, Counter>()

  fun get(key: K, loader: () -> V?): V? {
    val l1Val = l1Cache.getIfPresent(key)
    if (l1Val != null) {
      l1Hits?.increment()
      return l1Val
    }

    return l1Cache.get(key) {
      l1Misses?.increment()
      val keyStr = "$name:$key"
      if (l2Store != null && valueClass != null) {
        try {
          val l2Json = l2Store.get(keyStr)
          if (l2Json != null) {
            val l2Val = objectMapper.readValue(l2Json, valueClass)
            l2Hits?.increment()
            return@get l2Val
          }
          l2Misses?.increment()
        } catch (e: Exception) {
          log.warn("Fail-open: L2 cache read error for key {}: {}", keyStr, e.message)
        }
      }

      val loaded = loader()
      if (loaded != null && l2Store != null) {
        try {
          val json = objectMapper.writeValueAsString(loaded)
          l2Store.put(keyStr, json, l2Ttl)
        } catch (e: Exception) {
          log.warn("Fail-open: L2 cache write error for key {}: {}", keyStr, e.message)
        }
      }
      loaded
    }
  }

  fun put(key: K, value: V) {
    l1Cache.put(key, value)
    if (l2Store != null) {
      val keyStr = "$name:$key"
      try {
        val json = objectMapper.writeValueAsString(value)
        l2Store.put(keyStr, json, l2Ttl)
      } catch (e: Exception) {
        log.warn("Fail-open: L2 cache write error for key {}: {}", keyStr, e.message)
      }
    }
  }

  fun evict(key: K) {
    l1Cache.invalidate(key)
    if (l2Store != null) {
      val keyStr = "$name:$key"
      try {
        l2Store.delete(keyStr)
      } catch (e: Exception) {
        log.warn("Fail-open: L2 cache eviction error for key {}: {}", keyStr, e.message)
      }
    }
    evictionCounter("mutation")?.increment()
  }

  fun evictLocal(key: K) {
    l1Cache.invalidate(key)
    evictionCounter("event")?.increment()
  }

  fun evictAll() {
    l1Cache.invalidateAll()
    if (l2Store != null) {
      try {
        l2Store.deleteByPrefix("$name:")
      } catch (e: Exception) {
        log.warn("Fail-open: L2 cache clear error for cache {}: {}", name, e.message)
      }
    }
    evictionCounter("mutation")?.increment()
  }

  fun evictAllLocal() {
    l1Cache.invalidateAll()
    evictionCounter("event")?.increment()
  }

  private fun evictionCounter(reason: String): Counter? =
      meterRegistry?.let { registry ->
        evictionCounters.computeIfAbsent(reason) {
          registry.counter("hyperscale.cache.evictions", "cache", name, "reason", reason)
        }
      }
}

interface L2CacheStore {
  fun get(key: String): String?

  fun put(key: String, value: String, ttl: Duration)

  fun delete(key: String)

  fun deleteByPrefix(prefix: String)
}

class InMemoryL2CacheStore : L2CacheStore {
  private val store = ConcurrentHashMap<String, String>()

  override fun get(key: String): String? = store[key]

  override fun put(key: String, value: String, ttl: Duration) {
    store[key] = value
  }

  override fun delete(key: String) {
    store.remove(key)
  }

  override fun deleteByPrefix(prefix: String) {
    store.keys.removeIf { it.startsWith(prefix) }
  }
}

class RedisL2CacheStore(
    private val redisTemplate: org.springframework.data.redis.core.StringRedisTemplate,
) : L2CacheStore {
  override fun get(key: String): String? = redisTemplate.opsForValue().get(key)

  override fun put(key: String, value: String, ttl: Duration) {
    redisTemplate.opsForValue().set(key, value, ttl)
  }

  override fun delete(key: String) {
    redisTemplate.delete(key)
  }

  override fun deleteByPrefix(prefix: String) {
    val keys = redisTemplate.keys("$prefix*")
    if (!keys.isNullOrEmpty()) {
      redisTemplate.delete(keys)
    }
  }
}
