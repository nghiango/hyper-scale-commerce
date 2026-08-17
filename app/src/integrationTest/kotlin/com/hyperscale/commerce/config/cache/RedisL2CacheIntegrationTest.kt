package com.hyperscale.commerce.config.cache

import com.hyperscale.commerce.orderquery.config.cache.RedisL2CacheStore as QueryRedisL2CacheStore
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
class RedisL2CacheIntegrationTest {
  companion object {
    private const val PASSWORD = "integration_secret"

    @Container
    @JvmStatic
    val redis =
        RedisContainer(DockerImageName.parse("redis:7.2-alpine")).apply {
          withExposedPorts(6379)
          withCommand("redis-server", "--requirepass", PASSWORD, "--appendonly", "yes")
        }

    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var template: StringRedisTemplate

    @JvmStatic
    @BeforeAll
    fun connect() {
      val configuration = RedisStandaloneConfiguration(redis.host, redis.getMappedPort(6379))
      configuration.password = RedisPassword.of(PASSWORD)
      connectionFactory = LettuceConnectionFactory(configuration).apply { afterPropertiesSet() }
      template = StringRedisTemplate(connectionFactory).apply { afterPropertiesSet() }
    }

    @JvmStatic
    @AfterAll
    fun disconnect() {
      connectionFactory.destroy()
    }
  }

  @Test
  fun `app and query adapters share authenticated Redis state with TTL`() {
    val appStore = RedisL2CacheStore(template)
    val queryStore = QueryRedisL2CacheStore(template)

    appStore.put("phase17:shared", "payload", Duration.ofSeconds(30))

    assertThat(queryStore.get("phase17:shared")).isEqualTo("payload")
    assertThat(template.getExpire("phase17:shared")).isBetween(1L, 30L)

    queryStore.delete("phase17:shared")
    assertThat(appStore.get("phase17:shared")).isNull()
  }

  class RedisContainer(image: DockerImageName) : GenericContainer<RedisContainer>(image)
}
