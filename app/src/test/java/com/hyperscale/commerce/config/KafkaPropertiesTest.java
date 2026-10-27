package com.hyperscale.commerce.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KafkaPropertiesTest {

  @EnableConfigurationProperties(KafkaProperties.class)
  static class TestConfig {}

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

  @Test
  @DisplayName("binds default kafka properties")
  void bindsDefaultKafkaProperties() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          KafkaProperties props = context.getBean(KafkaProperties.class);
          assertThat(props.bootstrapServers()).isEqualTo("localhost:9092");
        });
  }

  @Test
  @DisplayName("binds custom bootstrap servers")
  void bindsCustomBootstrapServers() {
    contextRunner
        .withPropertyValues("spring.kafka.bootstrap-servers=kafka-cluster:9092")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              KafkaProperties props = context.getBean(KafkaProperties.class);
              assertThat(props.bootstrapServers()).isEqualTo("kafka-cluster:9092");
            });
  }

  @Test
  @DisplayName("fails validation when bootstrap servers is blank")
  void failsValidationWhenBootstrapServersIsBlank() {
    contextRunner
        .withPropertyValues("spring.kafka.bootstrap-servers= ")
        .run(context -> assertThat(context).hasFailed());
  }
}
