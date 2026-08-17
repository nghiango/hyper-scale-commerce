package com.hyperscale.commerce.orderquery.config.cache

import com.hyperscale.commerce.orderquery.application.cache.L2CacheStore
import java.time.Duration
import org.springframework.data.redis.core.StringRedisTemplate

class RedisL2CacheStore(private val redisTemplate: StringRedisTemplate) : L2CacheStore {
  override fun get(key: String): String? = redisTemplate.opsForValue().get(key)

  override fun put(key: String, value: String, ttl: Duration) {
    redisTemplate.opsForValue().set(key, value, ttl)
  }

  override fun delete(key: String) {
    redisTemplate.delete(key)
  }

  override fun deleteByPrefix(prefix: String) {
    val keys = redisTemplate.keys("$prefix*")
    if (!keys.isNullOrEmpty()) redisTemplate.delete(keys)
  }
}
