package com.hyperscale.commerce.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AppPropertiesTest {

  @EnableConfigurationProperties(AppProperties.class)
  static class TestConfig {}

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

  @Test
  @DisplayName("binds app properties from configuration")
  void bindsAppPropertiesFromConfiguration() {
    contextRunner
        .withPropertyValues("app.name=test-app", "app.instance-id=pod-a")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(AppProperties.class).name()).isEqualTo("test-app");
              assertThat(context.getBean(AppProperties.class).instanceId()).isEqualTo("pod-a");
            });
  }

  @Test
  @DisplayName("fails to start when app name is missing")
  void failsToStartWhenAppNameIsMissing() {
    contextRunner.run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("fails to start when app name is blank")
  void failsToStartWhenAppNameIsBlank() {
    contextRunner.withPropertyValues("app.name= ").run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("fails to start when instance id is blank")
  void failsToStartWhenInstanceIdIsBlank() {
    contextRunner
        .withPropertyValues("app.name=test-app", "app.instance-id= ")
        .run(context -> assertThat(context).hasFailed());
  }
}
