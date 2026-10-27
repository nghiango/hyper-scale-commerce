package com.hyperscale.commerce.orderquery.application.cache;

import java.time.Duration;

public interface L2CacheStore {
  String get(String key);

  void put(String key, String value, Duration ttl);

  void delete(String key);

  void deleteByPrefix(String prefix);
}
