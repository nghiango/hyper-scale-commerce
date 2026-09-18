package com.hyperscale.commerce.config.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class CacheConfig {

  @Bean
  public L2CacheStore redisL2CacheStore(StringRedisTemplate stringRedisTemplate) {
    return new RedisL2CacheStore(stringRedisTemplate);
  }
}
