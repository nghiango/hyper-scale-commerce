package com.hyperscale.commerce.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class KafkaPropertiesTest {

  @EnableConfigurationProperties(KafkaProperties::class) class TestConfig

  private val contextRunner =
      ApplicationContextRunner().withUserConfiguration(TestConfig::class.java)

  @Test
  fun `binds default kafka properties`() {
    contextRunner.run { context ->
      assertThat(context).hasNotFailed()
      val props = context.getBean(KafkaProperties::class.java)
      assertThat(props.bootstrapServers).isEqualTo("localhost:9092")
    }
  }

  @Test
  fun `binds custom bootstrap servers`() {
    contextRunner.withPropertyValues("spring.kafka.bootstrap-servers=kafka-cluster:9092").run {
        context ->
      assertThat(context).hasNotFailed()
      val props = context.getBean(KafkaProperties::class.java)
      assertThat(props.bootstrapServers).isEqualTo("kafka-cluster:9092")
    }
  }

  @Test
  fun `fails validation when bootstrap servers is blank`() {
    contextRunner.withPropertyValues("spring.kafka.bootstrap-servers= ").run { context ->
      assertThat(context).hasFailed()
    }
  }
}
