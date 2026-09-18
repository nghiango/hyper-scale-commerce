package com.hyperscale.commerce.config.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
public class RedisL2CacheIntegrationTest {

  private static final String PASSWORD = "integration_secret";

  @Container
  static final RedisContainer redis =
      new RedisContainer(DockerImageName.parse("redis:7.2-alpine"))
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", PASSWORD, "--appendonly", "yes");

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate template;

  @BeforeAll
  static void connect() {
    RedisStandaloneConfiguration configuration =
        new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
    configuration.setPassword(RedisPassword.of(PASSWORD));
    connectionFactory = new LettuceConnectionFactory(configuration);
    connectionFactory.afterPropertiesSet();
    template = new StringRedisTemplate(connectionFactory);
    template.afterPropertiesSet();
  }

  @AfterAll
  static void disconnect() {
    connectionFactory.destroy();
  }

  @Test
  @DisplayName("app and query adapters share authenticated Redis state with TTL")
  void appAndQueryAdaptersShareAuthenticatedRedisStateWithTtl() {
    RedisL2CacheStore appStore = new RedisL2CacheStore(template);
    com.hyperscale.commerce.orderquery.config.cache.RedisL2CacheStore queryStore =
        new com.hyperscale.commerce.orderquery.config.cache.RedisL2CacheStore(template);

    appStore.put("phase17:shared", "payload", Duration.ofSeconds(30));

    assertThat(queryStore.get("phase17:shared")).isEqualTo("payload");
    assertThat(template.getExpire("phase17:shared")).isBetween(1L, 30L);

    queryStore.delete("phase17:shared");
    assertThat(appStore.get("phase17:shared")).isNull();
  }

  public static class RedisContainer extends GenericContainer<RedisContainer> {

    public RedisContainer(DockerImageName image) {
      super(image);
    }
  }
}
