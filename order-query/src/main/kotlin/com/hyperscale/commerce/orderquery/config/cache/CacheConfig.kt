package com.hyperscale.commerce.orderquery.config.cache

import com.hyperscale.commerce.orderquery.application.cache.L2CacheStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate

@Configuration
class CacheConfig {
  @Bean
  fun l2CacheStore(redisTemplate: StringRedisTemplate): L2CacheStore =
      RedisL2CacheStore(redisTemplate)
}
