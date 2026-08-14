package com.hyperscale.commerce.modules.inventory

import com.hyperscale.commerce.config.OutboxProperties
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

private const val POISON_PAYLOAD = """{"version":1,"orderId":99}"""
private const val TIMEOUT_SECONDS = 20L
private const val POLL_MILLIS = 500L

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class InventoryFailureTest
@Autowired
constructor(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val outboxProperties: OutboxProperties,
) {

  companion object {
    @Container
    @JvmStatic
    val kafka: KafkaContainer = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))

    @Container
    @JvmStatic
    @ServiceConnection
    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

    @JvmStatic
    @DynamicPropertySource
    fun kafkaProperties(registry: DynamicPropertyRegistry) {
      registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
    }
  }

  @Test
  fun `poison message lands in the DLQ after bounded retries`() {
    kafkaTemplate
        .send(outboxProperties.topic, "99", POISON_PAYLOAD)
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    val dlqTopic = outboxProperties.topic + "-dlq"
    val record = awaitDlqRecord(dlqTopic)
    assertThat(record.value()).isEqualTo(POISON_PAYLOAD)
  }

  private fun awaitDlqRecord(topic: String): ConsumerRecord<String, String> {
    val props = HashMap<String, Any>()
    props[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = kafka.bootstrapServers
    props[ConsumerConfig.GROUP_ID_CONFIG] = "dlq-test-group"
    props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
    props[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
    props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
    KafkaConsumer<String, String>(props).use { consumer ->
      consumer.subscribe(listOf(topic))
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
      while (System.nanoTime() < deadline) {
        val record = consumer.poll(Duration.ofMillis(POLL_MILLIS)).firstOrNull()
        if (record != null) {
          return record
        }
      }
      throw AssertionError("No record in DLQ topic $topic within $TIMEOUT_SECONDS seconds")
    }
  }
}
