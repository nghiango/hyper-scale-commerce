package com.hyperscale.commerce.orderquery.application.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

public class NearCache<K, V> {
  private static final Logger log = LoggerFactory.getLogger(NearCache.class);

  private final String name;
  private final Duration l2Ttl;
  private final L2CacheStore l2Store;
  private final Class<V> valueClass;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;
  private final Cache<K, V> l1;
  private final Counter l1Hits;
  private final Counter l1Misses;
  private final Counter l2Hits;
  private final Counter l2Misses;
  private final ConcurrentHashMap<String, Counter> evictionCounters = new ConcurrentHashMap<>();

  public NearCache(
      String name,
      long l1MaxSize,
      Duration l1Ttl,
      Duration l2Ttl,
      L2CacheStore l2Store,
      Class<V> valueClass,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    this.name = name;
    this.l2Ttl = l2Ttl;
    this.l2Store = l2Store;
    this.valueClass = valueClass;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
    this.l1 =
        Caffeine.newBuilder()
            .maximumSize(l1MaxSize)
            .expireAfterWrite(l1Ttl)
            .<K, V>removalListener(
                (key, value, cause) -> {
                  switch (cause) {
                    case EXPIRED -> {
                      Counter counter = evictionCounter("ttl");
                      if (counter != null) {
                        counter.increment();
                      }
                    }
                    case SIZE -> {
                      Counter counter = evictionCounter("lru");
                      if (counter != null) {
                        counter.increment();
                      }
                    }
                    default -> {}
                  }
                })
            .build();
    this.l1Hits =
        meterRegistry != null
            ? meterRegistry.counter(
                "hyperscale.cache.gets", "cache", name, "level", "L1", "result", "hit")
            : null;
    this.l1Misses =
        meterRegistry != null
            ? meterRegistry.counter(
                "hyperscale.cache.gets", "cache", name, "level", "L1", "result", "miss")
            : null;
    this.l2Hits =
        meterRegistry != null
            ? meterRegistry.counter(
                "hyperscale.cache.gets", "cache", name, "level", "L2", "result", "hit")
            : null;
    this.l2Misses =
        meterRegistry != null
            ? meterRegistry.counter(
                "hyperscale.cache.gets", "cache", name, "level", "L2", "result", "miss")
            : null;
  }

  public NearCache(
      String name,
      long l1MaxSize,
      Duration l1Ttl,
      Duration l2Ttl,
      L2CacheStore l2Store,
      Class<V> valueClass,
      ObjectMapper objectMapper) {
    this(name, l1MaxSize, l1Ttl, l2Ttl, l2Store, valueClass, objectMapper, null);
  }

  public V get(K key, Supplier<V> loader) {
    V present = l1.getIfPresent(key);
    if (present != null) {
      if (l1Hits != null) {
        l1Hits.increment();
      }
      return present;
    }

    return l1.get(
        key,
        k -> {
          if (l1Misses != null) {
            l1Misses.increment();
          }
          String redisKey = name + ":" + key;
          try {
            String json = l2Store.get(redisKey);
            if (json != null) {
              if (l2Hits != null) {
                l2Hits.increment();
              }
              return objectMapper.readValue(json, valueClass);
            }
            if (l2Misses != null) {
              l2Misses.increment();
            }
          } catch (Exception exception) {
            log.warn(
                "Fail-open: L2 cache read error for key {}: {}", redisKey, exception.getMessage());
          }

          V value = loader.get();
          if (value != null) {
            try {
              l2Store.put(redisKey, objectMapper.writeValueAsString(value), l2Ttl);
            } catch (Exception exception) {
              log.warn(
                  "Fail-open: L2 cache write error for key {}: {}",
                  redisKey,
                  exception.getMessage());
            }
          }
          return value;
        });
  }

  public void evict(K key) {
    l1.invalidate(key);
    try {
      l2Store.delete(name + ":" + key);
    } catch (Exception exception) {
      log.warn("Fail-open: L2 cache eviction error for cache {}: {}", name, exception.getMessage());
    }
    Counter counter = evictionCounter("mutation");
    if (counter != null) {
      counter.increment();
    }
  }

  public void evictLocal(K key) {
    l1.invalidate(key);
    try {
      // An in-flight Caffeine load can repopulate L2 before invalidate returns. Repeating the
      // shared eviction on every invalidation consumer prevents that stale value resurfacing.
      l2Store.delete(name + ":" + key);
    } catch (Exception exception) {
      log.warn("Fail-open: L2 event eviction error for cache {}: {}", name, exception.getMessage());
    }
    Counter counter = evictionCounter("event");
    if (counter != null) {
      counter.increment();
    }
  }

  public void evictAll() {
    l1.invalidateAll();
    try {
      l2Store.deleteByPrefix(name + ":");
    } catch (Exception exception) {
      log.warn("Fail-open: L2 cache clear error for cache {}: {}", name, exception.getMessage());
    }
    Counter counter = evictionCounter("mutation");
    if (counter != null) {
      counter.increment();
    }
  }

  public void evictAllLocal() {
    l1.invalidateAll();
    try {
      l2Store.deleteByPrefix(name + ":");
    } catch (Exception exception) {
      log.warn("Fail-open: L2 event clear error for cache {}: {}", name, exception.getMessage());
    }
    Counter counter = evictionCounter("event");
    if (counter != null) {
      counter.increment();
    }
  }

  public V localValue(K key) {
    return l1.getIfPresent(key);
  }

  public String getName() {
    return name;
  }

  private Counter evictionCounter(String reason) {
    if (meterRegistry == null) {
      return null;
    }
    return evictionCounters.computeIfAbsent(
        reason,
        r -> meterRegistry.counter("hyperscale.cache.evictions", "cache", name, "reason", r));
  }
}
