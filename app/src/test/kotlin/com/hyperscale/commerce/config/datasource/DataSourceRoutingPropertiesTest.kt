package com.hyperscale.commerce.config.datasource

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class DataSourceRoutingPropertiesTest {

  @EnableConfigurationProperties(DataSourceRoutingProperties::class) class TestConfig

  private val contextRunner =
      ApplicationContextRunner().withUserConfiguration(TestConfig::class.java)

  @Test
  fun `binds default datasource routing properties`() {
    contextRunner.run { context ->
      assertThat(context).hasNotFailed()
      val props = context.getBean(DataSourceRoutingProperties::class.java)
      assertThat(props.primary.maximumPoolSize).isEqualTo(30)
      assertThat(props.replica.maximumPoolSize).isEqualTo(20)
      assertThat(props.replica.maxLagMs).isEqualTo(100)
      assertThat(props.replica.lagCheckIntervalMs).isEqualTo(1000)
    }
  }

  @Test
  fun `binds custom datasource routing properties`() {
    contextRunner
        .withPropertyValues(
            "app.datasource.primary.url=jdbc:postgresql://primary:5432/db",
            "app.datasource.primary.maximum-pool-size=50",
            "app.datasource.replica.url=jdbc:postgresql://replica:5432/db",
            "app.datasource.replica.maximum-pool-size=35",
            "app.datasource.replica.max-lag-ms=250",
            "app.datasource.replica.lag-check-interval-ms=2000",
        )
        .run { context ->
          assertThat(context).hasNotFailed()
          val props = context.getBean(DataSourceRoutingProperties::class.java)
          assertThat(props.primary.url).isEqualTo("jdbc:postgresql://primary:5432/db")
          assertThat(props.primary.maximumPoolSize).isEqualTo(50)
          assertThat(props.replica.url).isEqualTo("jdbc:postgresql://replica:5432/db")
          assertThat(props.replica.maximumPoolSize).isEqualTo(35)
          assertThat(props.replica.maxLagMs).isEqualTo(250)
          assertThat(props.replica.lagCheckIntervalMs).isEqualTo(2000)
        }
  }

  @Test
  fun `fails validation when maximumPoolSize is less than 1`() {
    contextRunner.withPropertyValues("app.datasource.primary.maximum-pool-size=0").run { context ->
      assertThat(context).hasFailed()
    }
  }

  @Test
  fun `fails validation when maxLagMs is less than 1`() {
    contextRunner.withPropertyValues("app.datasource.replica.max-lag-ms=0").run { context ->
      assertThat(context).hasFailed()
    }
  }
}
