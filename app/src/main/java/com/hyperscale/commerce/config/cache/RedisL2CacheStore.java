package com.hyperscale.commerce.config.cache;

import java.time.Duration;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisL2CacheStore implements L2CacheStore {

  private final StringRedisTemplate redisTemplate;

  public RedisL2CacheStore(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  @Override
  public String get(String key) {
    return redisTemplate.opsForValue().get(key);
  }

  @Override
  public void put(String key, String value, Duration ttl) {
    redisTemplate.opsForValue().set(key, value, ttl);
  }

  @Override
  public void delete(String key) {
    redisTemplate.delete(key);
  }

  @Override
  public void deleteByPrefix(String prefix) {
    Set<String> keys = redisTemplate.keys(prefix + "*");
    if (keys != null && !keys.isEmpty()) {
      redisTemplate.delete(keys);
    }
  }
}
