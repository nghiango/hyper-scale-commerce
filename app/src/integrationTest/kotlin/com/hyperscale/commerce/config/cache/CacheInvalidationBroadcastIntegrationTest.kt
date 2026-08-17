package com.hyperscale.commerce.config.cache

import java.time.Duration
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
class CacheInvalidationBroadcastIntegrationTest {
  companion object {
    @Container
    @JvmStatic
    val kafka = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))
  }

  @Test
  fun `one invalidation event reaches two independent pod consumer groups`() {
    createTopic()
    val firstConsumer = consumer("cache-pod-a-${UUID.randomUUID()}")
    val secondConsumer = consumer("cache-pod-b-${UUID.randomUUID()}")
    val producerFactory =
        DefaultKafkaProducerFactory<String, String>(
            mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.ACKS_CONFIG to "all",
            ))

    try {
      firstConsumer.subscribe(listOf(CacheInvalidationService.TOPIC_CATALOG_CACHE_INVALIDATION))
      secondConsumer.subscribe(listOf(CacheInvalidationService.TOPIC_CATALOG_CACHE_INVALIDATION))
      awaitAssignment(firstConsumer)
      awaitAssignment(secondConsumer)

      val firstCache =
          NearCache<Long, CacheValue>("catalog_products", valueClass = CacheValue::class.java)
      val secondCache =
          NearCache<Long, CacheValue>("catalog_products", valueClass = CacheValue::class.java)
      firstCache.put(42L, CacheValue(42L, "stale"))
      secondCache.put(42L, CacheValue(42L, "stale"))
      val firstService = CacheInvalidationService().also { it.registerCache(firstCache) }
      val secondService = CacheInvalidationService().also { it.registerCache(secondCache) }

      val publisher = CacheInvalidationService(KafkaTemplate(producerFactory))
      publisher.publishInvalidation("catalog_products", "warmup")
      awaitRecord(firstConsumer)
      awaitRecord(secondConsumer)

      val executor = Executors.newFixedThreadPool(2)
      try {
        val firstRecord = executor.submit<String> { awaitRecord(firstConsumer) }
        val secondRecord = executor.submit<String> { awaitRecord(secondConsumer) }
        val startedAt = System.nanoTime()
        publisher.publishInvalidation("catalog_products", "42")

        firstService.onInvalidationMessage(firstRecord.get(5, TimeUnit.SECONDS))
        secondService.onInvalidationMessage(secondRecord.get(5, TimeUnit.SECONDS))
        val propagationMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis()

        assertThat(firstCache.l1Cache.getIfPresent(42L)).isNull()
        assertThat(secondCache.l1Cache.getIfPresent(42L)).isNull()
        assertThat(propagationMillis).isLessThan(50L)
      } finally {
        executor.shutdownNow()
      }
    } finally {
      firstConsumer.close()
      secondConsumer.close()
      producerFactory.destroy()
    }
  }

  private fun createTopic() {
    AdminClient.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers))
        .use { admin ->
          admin
              .createTopics(
                  listOf(
                      NewTopic(
                          CacheInvalidationService.TOPIC_CATALOG_CACHE_INVALIDATION,
                          1,
                          1,
                      )))
              .all()
              .get()
        }
  }

  private fun consumer(groupId: String): KafkaConsumer<String, String> {
    val properties = Properties()
    properties[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = kafka.bootstrapServers
    properties[ConsumerConfig.GROUP_ID_CONFIG] = groupId
    properties[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
    properties[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
    properties[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
    return KafkaConsumer(properties)
  }

  private fun awaitAssignment(consumer: KafkaConsumer<String, String>) {
    repeat(50) {
      consumer.poll(Duration.ofMillis(100))
      if (consumer.assignment().isNotEmpty()) return
    }
    error("Kafka consumer group did not receive a partition assignment")
  }

  private fun awaitRecord(consumer: KafkaConsumer<String, String>): String {
    repeat(50) {
      val records = consumer.poll(Duration.ofMillis(100))
      if (!records.isEmpty) return records.iterator().next().value()
    }
    error("Kafka invalidation event was not received")
  }

  private data class CacheValue(val id: Long = 0, val name: String = "")
}
