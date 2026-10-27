package com.hyperscale.commerce.contracts;

import java.time.Instant;

public record CacheInvalidationEvent(
    String cacheName, String key, Instant timestamp, String originInstanceId) {

  public CacheInvalidationEvent {
    if (timestamp == null) {
      timestamp = Instant.now();
    }
  }

  public CacheInvalidationEvent(String cacheName, String key, String originInstanceId) {
    this(cacheName, key, null, originInstanceId);
  }
}
