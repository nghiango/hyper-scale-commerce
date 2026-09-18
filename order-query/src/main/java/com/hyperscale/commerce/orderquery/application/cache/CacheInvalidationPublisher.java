package com.hyperscale.commerce.orderquery.application.cache;

@FunctionalInterface
public interface CacheInvalidationPublisher {
  void publish(String cacheName, String key);
}
