package com.hyperscale.commerce.orderquery.config.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DataSourceRoutingPropertiesTest {

  @EnableConfigurationProperties(DataSourceRoutingProperties.class)
  static class TestConfig {}

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

  @Test
  @DisplayName("binds default datasource routing properties")
  void bindsDefaultDatasourceRoutingProperties() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          DataSourceRoutingProperties props = context.getBean(DataSourceRoutingProperties.class);
          assertThat(props.primary().maximumPoolSize()).isEqualTo(30);
          assertThat(props.replica().maximumPoolSize()).isEqualTo(20);
          assertThat(props.replica().maxLagMs()).isEqualTo(100);
          assertThat(props.replica().lagCheckIntervalMs()).isEqualTo(1000);
        });
  }

  @Test
  @DisplayName("binds custom datasource routing properties")
  void bindsCustomDatasourceRoutingProperties() {
    contextRunner
        .withPropertyValues(
            "app.datasource.primary.url=jdbc:postgresql://primary:5432/db",
            "app.datasource.primary.maximum-pool-size=40",
            "app.datasource.replica.url=jdbc:postgresql://replica:5432/db",
            "app.datasource.replica.maximum-pool-size=25",
            "app.datasource.replica.max-lag-ms=150",
            "app.datasource.replica.lag-check-interval-ms=1500")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              DataSourceRoutingProperties props =
                  context.getBean(DataSourceRoutingProperties.class);
              assertThat(props.primary().url()).isEqualTo("jdbc:postgresql://primary:5432/db");
              assertThat(props.primary().maximumPoolSize()).isEqualTo(40);
              assertThat(props.replica().url()).isEqualTo("jdbc:postgresql://replica:5432/db");
              assertThat(props.replica().maximumPoolSize()).isEqualTo(25);
              assertThat(props.replica().maxLagMs()).isEqualTo(150);
              assertThat(props.replica().lagCheckIntervalMs()).isEqualTo(1500);
            });
  }

  @Test
  @DisplayName("fails validation when maximumPoolSize is less than 1")
  void failsValidationWhenMaximumPoolSizeIsLessThan1() {
    contextRunner
        .withPropertyValues("app.datasource.primary.maximum-pool-size=0")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("fails validation when maxLagMs is less than 1")
  void failsValidationWhenMaxLagMsIsLessThan1() {
    contextRunner
        .withPropertyValues("app.datasource.replica.max-lag-ms=0")
        .run(context -> assertThat(context).hasFailed());
  }
}
