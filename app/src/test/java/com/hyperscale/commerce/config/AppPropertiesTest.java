package com.hyperscale.commerce.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AppPropertiesTest {

  @EnableConfigurationProperties(AppProperties.class)
  static class TestConfig {
  }

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(TestConfig.class);

  @Test
  void bindsAppPropertiesFromConfiguration() {
    contextRunner.withPropertyValues("app.name=test-app").run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBean(AppProperties.class).name()).isEqualTo("test-app");
    });
  }

  @Test
  void failsToStartWhenAppNameIsMissing() {
    contextRunner.run(context -> assertThat(context).hasFailed());
  }

  @Test
  void failsToStartWhenAppNameIsBlank() {
    contextRunner.withPropertyValues("app.name= ").run(context -> assertThat(context).hasFailed());
  }
}
