package com.hyperscale.commerce.config.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

record TestProduct(long id, String name, double price) {}

class NearCacheTest {

  @Test
  @DisplayName("l1 cache hit returns value without calling loader or L2")
  void l1CacheHitReturnsValueWithoutCallingLoaderOrL2() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    InMemoryL2CacheStore l2Store = new InMemoryL2CacheStore();
    NearCache<Long, TestProduct> cache =
        new NearCache<>(
            "products", 100L, Duration.ofMinutes(1), meterRegistry, l2Store, TestProduct.class);

    AtomicInteger dbLoads = new AtomicInteger(0);
    Supplier<TestProduct> loader =
        () -> {
          dbLoads.incrementAndGet();
          return new TestProduct(1L, "Laptop", 999.99);
        };

    // First get -> DB load + populate L1 and L2
    TestProduct res1 = cache.get(1L, loader);
    assertThat(res1.name()).isEqualTo("Laptop");
    assertThat(dbLoads.get()).isEqualTo(1);

    // Second get -> L1 hit, 0 DB loads
    TestProduct res2 = cache.get(1L, loader);
    assertThat(res2.name()).isEqualTo("Laptop");
    assertThat(dbLoads.get()).isEqualTo(1);

    // Verify metrics
    Counter l1Hits =
        meterRegistry
            .find("hyperscale.cache.gets")
            .tag("level", "L1")
            .tag("result", "hit")
            .counter();
    assertThat(l1Hits != null ? l1Hits.count() : null).isEqualTo(1.0);
  }

  @Test
  @DisplayName("l1 miss with L2 hit populates L1 without calling loader")
  void l1MissWithL2HitPopulatesL1WithoutCallingLoader() {
    InMemoryL2CacheStore l2Store = new InMemoryL2CacheStore();
    NearCache<Long, TestProduct> cache1 = new NearCache<>("products", l2Store, TestProduct.class);

    // Pod 1 puts product in cache (L1 + L2)
    cache1.put(2L, new TestProduct(2L, "Phone", 499.99));

    // Pod 2 (fresh L1) reads from cache
    NearCache<Long, TestProduct> cache2 = new NearCache<>("products", l2Store, TestProduct.class);

    AtomicInteger dbLoads = new AtomicInteger(0);
    TestProduct res =
        cache2.get(
            2L,
            () -> {
              dbLoads.incrementAndGet();
              return new TestProduct(2L, "Phone from DB", 499.99);
            });

    assertThat(res.name()).isEqualTo("Phone");
    assertThat(dbLoads.get()).isEqualTo(0); // Zero DB loads because L2 had the value!

    // Subsequent read from Pod 2 hits L1
    TestProduct resL1 =
        cache2.get(
            2L,
            () -> {
              dbLoads.incrementAndGet();
              return null;
            });
    assertThat(resL1.name()).isEqualTo("Phone");
    assertThat(dbLoads.get()).isEqualTo(0);
  }

  @Test
  @DisplayName("fail-open resilience falls back to loader if L2 store throws exception")
  void failOpenResilienceFallsBackToLoaderIfL2StoreThrowsException() {
    L2CacheStore failingL2 =
        new L2CacheStore() {
          @Override
          public String get(String key) {
            throw new UncheckedIOException(new IOException("Redis connection refused"));
          }

          @Override
          public void put(String key, String value, Duration ttl) {
            throw new UncheckedIOException(new IOException("Redis timeout"));
          }

          @Override
          public void delete(String key) {
            throw new UncheckedIOException(new IOException("Redis timeout"));
          }

          @Override
          public void deleteByPrefix(String prefix) {
            throw new UncheckedIOException(new IOException("Redis timeout"));
          }
        };

    NearCache<Long, TestProduct> cache = new NearCache<>("products", failingL2, TestProduct.class);

    AtomicInteger dbLoads = new AtomicInteger(0);
    TestProduct res =
        cache.get(
            3L,
            () -> {
              dbLoads.incrementAndGet();
              return new TestProduct(3L, "Tablet", 299.99);
            });

    assertThat(res.name()).isEqualTo("Tablet");
    assertThat(dbLoads.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("evict removes item from both L1 and L2")
  void evictRemovesItemFromBothL1AndL2() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    InMemoryL2CacheStore l2Store = new InMemoryL2CacheStore();
    NearCache<Long, TestProduct> cache =
        new NearCache<>("products", meterRegistry, l2Store, TestProduct.class);

    cache.put(4L, new TestProduct(4L, "Monitor", 199.99));
    assertThat(l2Store.get("products:4")).isNotNull();

    cache.evict(4L);
    assertThat(cache.getL1Cache().getIfPresent(4L)).isNull();
    assertThat(l2Store.get("products:4")).isNull();
    Counter mutationCounter =
        meterRegistry.find("hyperscale.cache.evictions").tag("reason", "mutation").counter();
    assertThat(mutationCounter != null ? mutationCounter.count() : null).isEqualTo(1.0);

    cache.evictLocal(4L);
    Counter eventCounter =
        meterRegistry.find("hyperscale.cache.evictions").tag("reason", "event").counter();
    assertThat(eventCounter != null ? eventCounter.count() : null).isEqualTo(1.0);
  }

  @Test
  @DisplayName("invalidation waits for a blocked load and removes its result")
  void invalidationWaitsForABlockedLoadAndRemovesItsResult() throws Exception {
    NearCache<Long, TestProduct> cache =
        new NearCache<>("products", new InMemoryL2CacheStore(), TestProduct.class);
    AtomicReference<TestProduct> source = new AtomicReference<>(new TestProduct(5L, "stale", 1.0));
    CountDownLatch firstLoadStarted = new CountDownLatch(1);
    CountDownLatch allowFirstLoadToFinish = new CountDownLatch(1);
    AtomicInteger loads = new AtomicInteger();
    CountDownLatch evictionStarted = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);

    try {
      Future<TestProduct> result =
          executor.submit(
              () ->
                  cache.get(
                      5L,
                      () -> {
                        TestProduct snapshot = source.get();
                        if (loads.incrementAndGet() == 1) {
                          firstLoadStarted.countDown();
                          try {
                            if (!allowFirstLoadToFinish.await(5, TimeUnit.SECONDS)) {
                              throw new IllegalStateException("Check failed.");
                            }
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                          }
                        }
                        return snapshot;
                      }));

      assertThat(firstLoadStarted.await(5, TimeUnit.SECONDS)).isTrue();
      Future<?> eviction =
          executor.submit(
              () -> {
                evictionStarted.countDown();
                cache.evictLocal(5L);
              });
      assertThat(evictionStarted.await(5, TimeUnit.SECONDS)).isTrue();
      source.set(new TestProduct(5L, "fresh", 2.0));
      allowFirstLoadToFinish.countDown();

      TestProduct firstResult = result.get(5, TimeUnit.SECONDS);
      assertThat(firstResult != null ? firstResult.name() : null).isEqualTo("stale");
      eviction.get(5, TimeUnit.SECONDS);
      TestProduct secondResult =
          cache.get(
              5L,
              () -> {
                loads.incrementAndGet();
                return source.get();
              });
      assertThat(secondResult != null ? secondResult.name() : null).isEqualTo("fresh");
      assertThat(loads.get()).isEqualTo(2);
    } finally {
      allowFirstLoadToFinish.countDown();
      executor.shutdownNow();
    }
  }
}
