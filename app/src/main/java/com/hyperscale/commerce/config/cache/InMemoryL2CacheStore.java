package com.hyperscale.commerce.config.cache;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryL2CacheStore implements L2CacheStore {

  private final Map<String, String> store = new ConcurrentHashMap<>();

  @Override
  public String get(String key) {
    return store.get(key);
  }

  @Override
  public void put(String key, String value, Duration ttl) {
    store.put(key, value);
  }

  @Override
  public void delete(String key) {
    store.remove(key);
  }

  @Override
  public void deleteByPrefix(String prefix) {
    store.keySet().removeIf(key -> key.startsWith(prefix));
  }
}
