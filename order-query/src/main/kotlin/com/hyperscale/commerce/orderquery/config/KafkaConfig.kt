package com.hyperscale.commerce.orderquery.config

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.function.Supplier
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.MessageListenerContainer
import org.springframework.util.backoff.FixedBackOff

@Configuration
@EnableKafka
class KafkaConfig {

  @Bean
  fun kafkaProducerFactory(
      @Value("\${spring.kafka.bootstrap-servers}") bootstrapServers: String,
  ): ProducerFactory<String, String> {
    val props = HashMap<String, Any>()
    props[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = bootstrapServers
    props[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
    props[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
    props[ProducerConfig.MAX_BLOCK_MS_CONFIG] = MAX_BLOCK_MS
    return DefaultKafkaProducerFactory(props)
  }

  @Bean
  fun kafkaTemplate(
      producerFactory: ProducerFactory<String, String>,
  ): KafkaTemplate<String, String> = KafkaTemplate(producerFactory)

  @Bean
  fun kafkaHealthIndicator(
      producerFactory: ProducerFactory<String, String>,
  ): HealthIndicator = HealthIndicator {
    try {
      producerFactory.createProducer().use { producer -> producer.partitionsFor(HEALTH_TOPIC) }
      Health.up().build()
    } catch (exception: KafkaException) {
      Health.down(exception).build()
    }
  }

  @Bean
  fun kafkaConsumerFactory(
      @Value("\${spring.kafka.bootstrap-servers}") bootstrapServers: String,
  ): ConsumerFactory<String, String> {
    val props = HashMap<String, Any>()
    props[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = bootstrapServers
    props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
    props[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
    props[ConsumerConfig.GROUP_ID_CONFIG] = CONSUMER_GROUP
    props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
    props[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] = false
    return DefaultKafkaConsumerFactory(props)
  }

  @Bean
  fun kafkaListenerContainerFactory(
      consumerFactory: ConsumerFactory<String, String>,
      kafkaTemplate: KafkaTemplate<String, String>,
      meterRegistry: MeterRegistry,
  ): ConcurrentKafkaListenerContainerFactory<String, String> {
    val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
    factory.setConsumerFactory(consumerFactory)
    factory.containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE

    val dlqCounter = meterRegistry.counter("events_dlq_total", "topic", DLQ_TOPIC)
    val deadLetterRecoverer =
        DeadLetterPublishingRecoverer(kafkaTemplate) { record, _ ->
          dlqCounter.increment()
          TopicPartition(record.topic() + "-dlq", record.partition())
        }
    factory.setCommonErrorHandler(
        DefaultErrorHandler(deadLetterRecoverer, FixedBackOff(RETRY_BACKOFF_MS, MAX_RETRIES)))
    return factory
  }

  @Bean
  fun kafkaConsumerLagGauge(
      kafkaListenerEndpointRegistry: KafkaListenerEndpointRegistry,
      meterRegistry: MeterRegistry,
  ): Gauge {
    val lagSupplier =
        object : Supplier<Number> {
          override fun get(): Number {
            return kafkaListenerEndpointRegistry.getAllListenerContainers().maxOfOrNull { container
              ->
              consumerLag(container)
            } ?: 0.0
          }
        }
    return Gauge.builder("kafka_consumer_lag", lagSupplier).register(meterRegistry)
  }

  private fun consumerLag(container: MessageListenerContainer): Double {
    val metrics =
        when (container) {
          is ConcurrentMessageListenerContainer<*, *> -> container.metrics()
          is KafkaMessageListenerContainer<*, *> -> container.metrics()
          else -> return 0.0
        }
    val lagMetric =
        metrics.values
            .flatMap { it.entries }
            .firstOrNull { entry -> entry.key.name() == "records-lag-max" }
    return lagMetric?.value?.metricValue() as? Double ?: 0.0
  }

  private companion object {
    const val MAX_BLOCK_MS = 3000
    const val HEALTH_TOPIC = "health-check"
    const val CONSUMER_GROUP = "order-query"
    const val DLQ_TOPIC = "order-placed-dlq"
    const val RETRY_BACKOFF_MS = 1000L
    const val MAX_RETRIES = 3L
  }
}
