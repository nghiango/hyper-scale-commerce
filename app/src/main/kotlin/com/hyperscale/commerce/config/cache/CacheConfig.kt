package com.hyperscale.commerce.config.cache

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate

@Configuration
class CacheConfig {

  @Bean
  fun redisL2CacheStore(stringRedisTemplate: StringRedisTemplate): L2CacheStore {
    return RedisL2CacheStore(stringRedisTemplate)
  }
}
