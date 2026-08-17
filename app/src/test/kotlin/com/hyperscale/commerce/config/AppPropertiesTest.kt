package com.hyperscale.commerce.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class AppPropertiesTest {

  @EnableConfigurationProperties(AppProperties::class) class TestConfig

  private val contextRunner =
      ApplicationContextRunner().withUserConfiguration(TestConfig::class.java)

  @Test
  fun `binds app properties from configuration`() {
    contextRunner.withPropertyValues("app.name=test-app", "app.instance-id=pod-a").run { context ->
      assertThat(context).hasNotFailed()
      assertThat(context.getBean(AppProperties::class.java).name).isEqualTo("test-app")
      assertThat(context.getBean(AppProperties::class.java).instanceId).isEqualTo("pod-a")
    }
  }

  @Test
  fun `fails to start when app name is missing`() {
    contextRunner.run { context -> assertThat(context).hasFailed() }
  }

  @Test
  fun `fails to start when app name is blank`() {
    contextRunner.withPropertyValues("app.name= ").run { context ->
      assertThat(context).hasFailed()
    }
  }

  @Test
  fun `fails to start when instance id is blank`() {
    contextRunner.withPropertyValues("app.name=test-app", "app.instance-id= ").run { context ->
      assertThat(context).hasFailed()
    }
  }
}
