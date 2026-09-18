package com.hyperscale.commerce.orderquery.config.cache;

import com.hyperscale.commerce.orderquery.application.cache.L2CacheStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class CacheConfig {
  @Bean
  public L2CacheStore l2CacheStore(StringRedisTemplate redisTemplate) {
    return new RedisL2CacheStore(redisTemplate);
  }
}
