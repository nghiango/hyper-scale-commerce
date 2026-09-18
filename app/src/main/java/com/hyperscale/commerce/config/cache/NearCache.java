package com.hyperscale.commerce.config.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public class NearCache<K, V> {

  private static final Logger log = LoggerFactory.getLogger(NearCache.class);

  private final String name;
  private final long l1MaxSize;
  private final Duration l1Ttl;
  private final Duration l2Ttl;
  private final MeterRegistry meterRegistry;
  private final ObjectMapper objectMapper;
  private final L2CacheStore l2Store;
  private final Class<V> valueClass;

  private final Cache<K, V> l1Cache;

  private final Counter l1Hits;
  private final Counter l1Misses;
  private final Counter l2Hits;
  private final Counter l2Misses;
  private final Map<String, Counter> evictionCounters = new ConcurrentHashMap<>();

  public NearCache(String name) {
    this(name, null);
  }

  public NearCache(String name, Class<V> valueClass) {
    this(name, null, valueClass);
  }

  public NearCache(String name, L2CacheStore l2Store, Class<V> valueClass) {
    this(name, null, l2Store, valueClass);
  }

  public NearCache(
      String name, MeterRegistry meterRegistry, L2CacheStore l2Store, Class<V> valueClass) {
    this(name, 10_000L, Duration.ofSeconds(60), meterRegistry, l2Store, valueClass);
  }

  public NearCache(
      String name,
      long l1MaxSize,
      Duration l1Ttl,
      MeterRegistry meterRegistry,
      L2CacheStore l2Store,
      Class<V> valueClass) {
    this(name, l1MaxSize, l1Ttl, Duration.ofMinutes(10), meterRegistry, null, l2Store, valueClass);
  }

  public NearCache(
      String name,
      long l1MaxSize,
      Duration l1Ttl,
      Duration l2Ttl,
      MeterRegistry meterRegistry,
      ObjectMapper objectMapper,
      L2CacheStore l2Store,
      Class<V> valueClass) {
    this.name = name;
    this.l1MaxSize = l1MaxSize;
    this.l1Ttl = l1Ttl != null ? l1Ttl : Duration.ofSeconds(60);
    this.l2Ttl = l2Ttl != null ? l2Ttl : Duration.ofMinutes(10);
    this.meterRegistry = meterRegistry;
    this.objectMapper =
        objectMapper != null ? objectMapper : JsonMapper.builder().findAndAddModules().build();
    this.l2Store = l2Store;
    this.valueClass = valueClass;

    this.l1Cache =
        Caffeine.newBuilder()
            .maximumSize(l1MaxSize)
            .expireAfterWrite(this.l1Ttl)
            .removalListener(this::onL1Removal)
            .build();

    this.l1Hits =
        this.meterRegistry != null
            ? this.meterRegistry.counter(
                "hyperscale.cache.gets", "cache", name, "level", "L1", "result", "hit")
            : null;
    this.l1Misses =
        this.meterRegistry != null
            ? this.meterRegistry.counter(
                "hyperscale.cache.gets", "cache", name, "level", "L1", "result", "miss")
            : null;
    this.l2Hits =
        this.meterRegistry != null
            ? this.meterRegistry.counter(
                "hyperscale.cache.gets", "cache", name, "level", "L2", "result", "hit")
            : null;
    this.l2Misses =
        this.meterRegistry != null
            ? this.meterRegistry.counter(
                "hyperscale.cache.gets", "cache", name, "level", "L2", "result", "miss")
            : null;
  }

  public String getName() {
    return name;
  }

  public long getL1MaxSize() {
    return l1MaxSize;
  }

  public Duration getL1Ttl() {
    return l1Ttl;
  }

  public Duration getL2Ttl() {
    return l2Ttl;
  }

  public Cache<K, V> getL1Cache() {
    return l1Cache;
  }

  public V get(K key, Supplier<V> loader) {
    V l1Val = l1Cache.getIfPresent(key);
    if (l1Val != null) {
      if (l1Hits != null) {
        l1Hits.increment();
      }
      return l1Val;
    }

    return l1Cache.get(
        key,
        k -> {
          if (l1Misses != null) {
            l1Misses.increment();
          }
          String keyStr = name + ":" + key;
          if (l2Store != null && valueClass != null) {
            try {
              String l2Json = l2Store.get(keyStr);
              if (l2Json != null) {
                V l2Val = objectMapper.readValue(l2Json, valueClass);
                if (l2Hits != null) {
                  l2Hits.increment();
                }
                return l2Val;
              }
              if (l2Misses != null) {
                l2Misses.increment();
              }
            } catch (Exception e) {
              log.warn("Fail-open: L2 cache read error for key {}: {}", keyStr, e.getMessage());
            }
          }

          V loaded = loader.get();
          if (loaded != null && l2Store != null) {
            try {
              String json = objectMapper.writeValueAsString(loaded);
              l2Store.put(keyStr, json, l2Ttl);
            } catch (Exception e) {
              log.warn("Fail-open: L2 cache write error for key {}: {}", keyStr, e.getMessage());
            }
          }
          return loaded;
        });
  }

  public void put(K key, V value) {
    l1Cache.put(key, value);
    if (l2Store != null) {
      String keyStr = name + ":" + key;
      try {
        String json = objectMapper.writeValueAsString(value);
        l2Store.put(keyStr, json, l2Ttl);
      } catch (Exception e) {
        log.warn("Fail-open: L2 cache write error for key {}: {}", keyStr, e.getMessage());
      }
    }
  }

  public void evict(K key) {
    l1Cache.invalidate(key);
    if (l2Store != null) {
      String keyStr = name + ":" + key;
      try {
        l2Store.delete(keyStr);
      } catch (Exception e) {
        log.warn("Fail-open: L2 cache eviction error for key {}: {}", keyStr, e.getMessage());
      }
    }
    Counter counter = evictionCounter("mutation");
    if (counter != null) {
      counter.increment();
    }
  }

  public void evictLocal(K key) {
    l1Cache.invalidate(key);
    if (l2Store != null) {
      String keyStr = name + ":" + key;
      try {
        // An in-flight Caffeine load can repopulate L2 before invalidate returns. Repeating the
        // shared eviction on every invalidation consumer prevents that stale value resurfacing.
        l2Store.delete(keyStr);
      } catch (Exception e) {
        log.warn("Fail-open: L2 event eviction error for key {}: {}", keyStr, e.getMessage());
      }
    }
    Counter counter = evictionCounter("event");
    if (counter != null) {
      counter.increment();
    }
  }

  public void evictAll() {
    l1Cache.invalidateAll();
    if (l2Store != null) {
      try {
        l2Store.deleteByPrefix(name + ":");
      } catch (Exception e) {
        log.warn("Fail-open: L2 cache clear error for cache {}: {}", name, e.getMessage());
      }
    }
    Counter counter = evictionCounter("mutation");
    if (counter != null) {
      counter.increment();
    }
  }

  public void evictAllLocal() {
    l1Cache.invalidateAll();
    if (l2Store != null) {
      try {
        l2Store.deleteByPrefix(name + ":");
      } catch (Exception e) {
        log.warn("Fail-open: L2 event clear error for cache {}: {}", name, e.getMessage());
      }
    }
    Counter counter = evictionCounter("event");
    if (counter != null) {
      counter.increment();
    }
  }

  private void onL1Removal(K key, V value, RemovalCause cause) {
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
