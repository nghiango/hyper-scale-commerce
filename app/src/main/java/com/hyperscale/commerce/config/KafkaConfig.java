package com.hyperscale.commerce.config;

import com.hyperscale.commerce.config.backoff.ExponentialBackOffWithJitter;
import com.hyperscale.commerce.config.observability.CorrelationIdRecordInterceptor;
import com.hyperscale.commerce.config.observability.KafkaCorrelationProducerInterceptor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import tools.jackson.core.JacksonException;

@Configuration
@EnableKafka
public class KafkaConfig {

  private static final int MAX_BLOCK_MS = 3000;
  private static final int MAX_IN_FLIGHT_REQUESTS = 5;
  private static final String HEALTH_TOPIC = "health-check";
  private static final String CONSUMER_GROUP = "inventory";
  private static final String DLQ_TOPIC = "order-placed-dlq";
  private static final long INITIAL_INTERVAL_MS = 200L;
  private static final double BACKOFF_MULTIPLIER = 2.0;
  private static final long MAX_INTERVAL_MS = 2000L;
  private static final long MAX_RETRIES = 3L;

  private final ObservationRegistry observationRegistry;
  private final Tracer tracer;

  public KafkaConfig(ObservationRegistry observationRegistry, Tracer tracer) {
    this.observationRegistry = observationRegistry;
    this.tracer = tracer;
  }

  @Bean
  public ProducerFactory<String, String> kafkaProducerFactory(KafkaProperties kafkaProperties) {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.bootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, MAX_BLOCK_MS);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
    props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, MAX_IN_FLIGHT_REQUESTS);
    props.put(
        ProducerConfig.INTERCEPTOR_CLASSES_CONFIG,
        List.of(KafkaCorrelationProducerInterceptor.class.getName()));
    return new DefaultKafkaProducerFactory<>(props);
  }

  @Bean
  public KafkaTemplate<String, String> kafkaTemplate(
      ProducerFactory<String, String> producerFactory) {
    KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
    template.setObservationEnabled(true);
    template.setObservationRegistry(observationRegistry);
    return template;
  }

  @Bean
  public HealthIndicator kafkaHealthIndicator(ProducerFactory<String, String> producerFactory) {
    return () -> {
      try (Producer<String, String> producer = producerFactory.createProducer()) {
        producer.partitionsFor(HEALTH_TOPIC);
        return Health.up().build();
      } catch (KafkaException exception) {
        return Health.down(exception).build();
      }
    };
  }

  @Bean
  public ConsumerFactory<String, String> kafkaConsumerFactory(KafkaProperties kafkaProperties) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.bootstrapServers());
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, CONSUMER_GROUP);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    return new DefaultKafkaConsumerFactory<>(props);
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
      ConsumerFactory<String, String> consumerFactory,
      KafkaTemplate<String, String> kafkaTemplate,
      MeterRegistry meterRegistry) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.setRecordInterceptor(new CorrelationIdRecordInterceptor(observationRegistry, tracer));
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

    Counter dlqCounter = meterRegistry.counter("events_dlq_total", "topic", DLQ_TOPIC);
    DeadLetterPublishingRecoverer deadLetterRecoverer =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, exception) -> {
              dlqCounter.increment();
              return new TopicPartition(record.topic() + "-dlq", record.partition());
            });
    ExponentialBackOffWithJitter backOff = new ExponentialBackOffWithJitter(MAX_RETRIES);
    backOff.setInitialInterval(INITIAL_INTERVAL_MS);
    backOff.setMultiplier(BACKOFF_MULTIPLIER);
    backOff.setMaxInterval(MAX_INTERVAL_MS);
    DefaultErrorHandler errorHandler = new DefaultErrorHandler(deadLetterRecoverer, backOff);
    errorHandler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class);
    factory.setCommonErrorHandler(errorHandler);
    return factory;
  }

  @Bean
  public Gauge kafkaConsumerLagGauge(
      KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry, MeterRegistry meterRegistry) {
    Supplier<Number> lagSupplier =
        new Supplier<Number>() {
          @Override
          public Number get() {
            return kafkaListenerEndpointRegistry.getAllListenerContainers().stream()
                .mapToDouble(KafkaConfig.this::consumerLag)
                .max()
                .orElse(0.0);
          }
        };
    return Gauge.builder("kafka_consumer_lag", lagSupplier).register(meterRegistry);
  }

  private double consumerLag(MessageListenerContainer container) {
    Map<String, Map<MetricName, ? extends Metric>> metrics;
    if (container instanceof ConcurrentMessageListenerContainer<?, ?> concurrent) {
      metrics = concurrent.metrics();
    } else if (container instanceof KafkaMessageListenerContainer<?, ?> single) {
      metrics = single.metrics();
    } else {
      return 0.0;
    }
    Map.Entry<MetricName, ? extends Metric> lagMetric =
        metrics.values().stream()
            .flatMap(map -> map.entrySet().stream())
            .filter(entry -> entry.getKey().name().equals("records-lag-max"))
            .findFirst()
            .orElse(null);
    if (lagMetric == null) {
      return 0.0;
    }
    Object metricValue = lagMetric.getValue().metricValue();
    return metricValue instanceof Double value ? value : 0.0;
  }
}
