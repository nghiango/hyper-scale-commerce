package com.hyperscale.commerce.modules.shared.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.hyperscale.commerce.config.OutboxProperties
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

private const val EVENT_TYPE = "OrderPlaced"
private const val FIRST_AGGREGATE_ID = "order-1"
private const val SECOND_AGGREGATE_ID = "order-2"
private const val RELAY_AGGREGATE_ID = "order-42"
private const val PAYLOAD =
    """{"version":1,"eventId":"event-1","orderId":1,"items":[{"sku":"PERF-SKU-00001","quantity":1}]}"""
private const val RELAY_PAYLOAD =
    """{"version":1,"eventId":"event-2","orderId":42,"items":[{"sku":"PERF-SKU-00001","quantity":2}]}"""
private const val CONSUMER_GROUP = "outbox-test-group"
private const val TIMEOUT_SECONDS = 10L
private const val POLL_MILLIS = 500L
private const val CLAIM_LIMIT = 10
private const val RELAY_INTERVAL_MS_DISABLED = 3_600_000L

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboxRelayIntegrationTest
@Autowired
constructor(
    private val outboxRepository: OutboxRepository,
    private val outboxRelay: OutboxRelay,
    private val outboxProperties: OutboxProperties,
) {
  private val objectMapper = ObjectMapper()

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
      registry.add("app.outbox.relay-interval-ms") { RELAY_INTERVAL_MS_DISABLED }
    }
  }

  @Test
  fun `inserts and claims due outbox events in order`() {
    val firstId = outboxRepository.insert(FIRST_AGGREGATE_ID, EVENT_TYPE, PAYLOAD)
    val secondId = outboxRepository.insert(SECOND_AGGREGATE_ID, EVENT_TYPE, PAYLOAD)

    val claimed = outboxRepository.claimDue(1)
    assertThat(claimed).hasSize(1)
    assertThat(claimed.first().id).isEqualTo(firstId)
    assertThat(claimed.first().aggregateId).isEqualTo(FIRST_AGGREGATE_ID)
    assertThat(claimed.first().eventType).isEqualTo(EVENT_TYPE)
    assertThat(objectMapper.readTree(claimed.first().payload))
        .isEqualTo(objectMapper.readTree(PAYLOAD))
    assertThat(claimed.first().publishedAt).isNull()

    outboxRepository.markPublished(firstId)
    val remaining = outboxRepository.claimDue(CLAIM_LIMIT)
    assertThat(remaining.map { it.id }).containsExactly(secondId)
  }

  @Test
  fun `relay publishes due events to Kafka and marks them published`() {
    val eventId = outboxRepository.insert(RELAY_AGGREGATE_ID, EVENT_TYPE, RELAY_PAYLOAD)

    outboxRelay.publishDueEvents()

    val received = consumeFirst(RELAY_AGGREGATE_ID)
    assertThat(objectMapper.readTree(received)).isEqualTo(objectMapper.readTree(RELAY_PAYLOAD))

    val remaining = outboxRepository.claimDue(CLAIM_LIMIT)
    assertThat(remaining.map { it.id }).doesNotContain(eventId)
  }

  private fun consumeFirst(key: String): String {
    val props = Properties()
    props["bootstrap.servers"] = kafka.bootstrapServers
    props["group.id"] = CONSUMER_GROUP
    props["key.deserializer"] = StringDeserializer::class.java.name
    props["value.deserializer"] = StringDeserializer::class.java.name
    props["auto.offset.reset"] = "earliest"

    KafkaConsumer<String, String>(props).use { consumer ->
      consumer.subscribe(listOf(outboxProperties.topic))
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
      while (System.nanoTime() < deadline) {
        val records = consumer.poll(Duration.ofMillis(POLL_MILLIS))
        val match = records.firstOrNull { it.key() == key }
        if (match != null) {
          return match.value()
        }
      }
      throw AssertionError("no message received for key $key within ${TIMEOUT_SECONDS}s")
    }
  }
}
