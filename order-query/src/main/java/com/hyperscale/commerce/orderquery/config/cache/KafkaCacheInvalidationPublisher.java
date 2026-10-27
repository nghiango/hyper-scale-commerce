package com.hyperscale.commerce.orderquery.config.cache;

import com.hyperscale.commerce.contracts.CacheInvalidationEvent;
import com.hyperscale.commerce.orderquery.application.cache.CacheInvalidationPublisher;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class KafkaCacheInvalidationPublisher implements CacheInvalidationPublisher {
  private static final Logger logger =
      LoggerFactory.getLogger(KafkaCacheInvalidationPublisher.class);

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final String instanceId = UUID.randomUUID().toString();

  public KafkaCacheInvalidationPublisher(
      KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public void publish(String cacheName, String key) {
    CacheInvalidationEvent event = new CacheInvalidationEvent(cacheName, key, instanceId);
    try {
      String payload = objectMapper.writeValueAsString(event);
      kafkaTemplate.send(
          CacheInvalidationListener.TOPIC_ORDER_CACHE_INVALIDATION,
          key != null ? key : cacheName,
          payload);
    } catch (Exception exception) {
      logger.warn("Failed to publish cache invalidation for {}: {}", key, exception.getMessage());
    }
  }
}
