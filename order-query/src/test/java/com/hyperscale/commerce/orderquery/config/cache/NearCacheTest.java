package com.hyperscale.commerce.orderquery.config.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyperscale.commerce.orderquery.application.cache.L2CacheStore;
import com.hyperscale.commerce.orderquery.application.cache.NearCache;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class NearCacheTest {
  private record CacheValue(long id, String name) {}

  @Test
  @DisplayName("shared L2 value warms a second pod cache without invoking its loader")
  void sharedL2ValueWarmsASecondPodCacheWithoutInvokingItsLoader() {
    MemoryStore store = new MemoryStore();
    NearCache<Long, CacheValue> first = cache(store);
    NearCache<Long, CacheValue> second = cache(store);

    assertThat(first.get(1L, () -> new CacheValue(1L, "shared")))
        .isEqualTo(new CacheValue(1L, "shared"));
    AtomicInteger loads = new AtomicInteger();
    CacheValue fromSecond =
        second.get(
            1L,
            () -> {
              loads.incrementAndGet();
              return new CacheValue(1L, "database");
            });

    assertThat(fromSecond).isEqualTo(new CacheValue(1L, "shared"));
    assertThat(loads.get()).isZero();
  }

  @Test
  @DisplayName("L2 failure fails open to loader")
  void l2FailureFailsOpenToLoader() {
    L2CacheStore failingStore =
        new L2CacheStore() {
          @Override
          public String get(String key) {
            throw new IllegalStateException("redis unavailable");
          }

          @Override
          public void put(String key, String value, Duration ttl) {
            throw new IllegalStateException("redis unavailable");
          }

          @Override
          public void delete(String key) {
            throw new IllegalStateException("redis unavailable");
          }

          @Override
          public void deleteByPrefix(String prefix) {
            throw new IllegalStateException("redis unavailable");
          }
        };

    assertThat(cache(failingStore).get(2L, () -> new CacheValue(2L, "database")))
        .isEqualTo(new CacheValue(2L, "database"));
  }

  @Test
  @DisplayName("invalidation waits for a blocked load and removes its result")
  void invalidationWaitsForABlockedLoadAndRemovesItsResult() throws Exception {
    MemoryStore store = new MemoryStore();
    NearCache<Long, CacheValue> cache = cache(store);
    AtomicReference<CacheValue> source = new AtomicReference<>(new CacheValue(3L, "stale"));
    CountDownLatch firstLoadStarted = new CountDownLatch(1);
    CountDownLatch allowFirstLoadToFinish = new CountDownLatch(1);
    AtomicInteger loads = new AtomicInteger();
    CountDownLatch evictionStarted = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);

    try {
      Future<CacheValue> result =
          executor.submit(
              () ->
                  cache.get(
                      3L,
                      () -> {
                        CacheValue snapshot = source.get();
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
                cache.evictLocal(3L);
              });
      assertThat(evictionStarted.await(5, TimeUnit.SECONDS)).isTrue();
      source.set(new CacheValue(3L, "fresh"));
      allowFirstLoadToFinish.countDown();

      assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(new CacheValue(3L, "stale"));
      eviction.get(5, TimeUnit.SECONDS);
      assertThat(
              cache.get(
                  3L,
                  () -> {
                    loads.incrementAndGet();
                    return source.get();
                  }))
          .isEqualTo(new CacheValue(3L, "fresh"));
      assertThat(loads.get()).isEqualTo(2);
    } finally {
      allowFirstLoadToFinish.countDown();
      executor.shutdownNow();
    }
  }

  private NearCache<Long, CacheValue> cache(L2CacheStore store) {
    return new NearCache<>(
        "orders",
        100,
        Duration.ofMinutes(1),
        Duration.ofMinutes(10),
        store,
        CacheValue.class,
        new ObjectMapper());
  }

  private static class MemoryStore implements L2CacheStore {
    private final ConcurrentHashMap<String, String> values = new ConcurrentHashMap<>();

    @Override
    public String get(String key) {
      return values.get(key);
    }

    @Override
    public void put(String key, String value, Duration ttl) {
      values.put(key, value);
    }

    @Override
    public void delete(String key) {
      values.remove(key);
    }

    @Override
    public void deleteByPrefix(String prefix) {
      values.keySet().removeIf(key -> key.startsWith(prefix));
    }
  }
}
