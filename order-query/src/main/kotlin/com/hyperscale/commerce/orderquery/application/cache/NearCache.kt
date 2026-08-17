@file:Suppress("LongParameterList", "TooGenericExceptionCaught")

package com.hyperscale.commerce.orderquery.application.cache

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper

class NearCache<K : Any, V : Any>(
    val name: String,
    l1MaxSize: Long,
    l1Ttl: Duration,
    private val l2Ttl: Duration,
    private val l2Store: L2CacheStore,
    private val valueClass: Class<V>,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry? = null,
) {
  private val log = LoggerFactory.getLogger(NearCache::class.java)
  private val l1: Cache<K, V> =
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
    l1.getIfPresent(key)?.let {
      l1Hits?.increment()
      return it
    }

    return l1.get(key) {
      l1Misses?.increment()
      val redisKey = "$name:$key"
      try {
        l2Store.get(redisKey)?.let { json ->
          l2Hits?.increment()
          return@get objectMapper.readValue(json, valueClass)
        }
        l2Misses?.increment()
      } catch (exception: Exception) {
        log.warn("Fail-open: L2 cache read error for key {}: {}", redisKey, exception.message)
      }

      loader()?.also { value ->
        try {
          l2Store.put(redisKey, objectMapper.writeValueAsString(value), l2Ttl)
        } catch (exception: Exception) {
          log.warn("Fail-open: L2 cache write error for key {}: {}", redisKey, exception.message)
        }
      }
    }
  }

  fun evict(key: K) {
    l1.invalidate(key)
    try {
      l2Store.delete("$name:$key")
    } catch (exception: Exception) {
      log.warn("Fail-open: L2 cache eviction error for cache {}: {}", name, exception.message)
    }
    evictionCounter("mutation")?.increment()
  }

  fun evictLocal(key: K) {
    l1.invalidate(key)
    try {
      // An in-flight Caffeine load can repopulate L2 before invalidate returns. Repeating the
      // shared eviction on every invalidation consumer prevents that stale value resurfacing.
      l2Store.delete("$name:$key")
    } catch (exception: Exception) {
      log.warn("Fail-open: L2 event eviction error for cache {}: {}", name, exception.message)
    }
    evictionCounter("event")?.increment()
  }

  fun evictAll() {
    l1.invalidateAll()
    try {
      l2Store.deleteByPrefix("$name:")
    } catch (exception: Exception) {
      log.warn("Fail-open: L2 cache clear error for cache {}: {}", name, exception.message)
    }
    evictionCounter("mutation")?.increment()
  }

  fun evictAllLocal() {
    l1.invalidateAll()
    try {
      l2Store.deleteByPrefix("$name:")
    } catch (exception: Exception) {
      log.warn("Fail-open: L2 event clear error for cache {}: {}", name, exception.message)
    }
    evictionCounter("event")?.increment()
  }

  fun localValue(key: K): V? = l1.getIfPresent(key)

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

fun interface CacheInvalidationPublisher {
  fun publish(cacheName: String, key: String?)
}
