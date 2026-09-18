package com.hyperscale.commerce.config.cache;

import com.hyperscale.commerce.contracts.CacheInvalidationEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CacheInvalidationService {

  public static final String TOPIC_CATALOG_CACHE_INVALIDATION = "catalog-cache-evict";

  private static final Logger log = LoggerFactory.getLogger(CacheInvalidationService.class);

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final String instanceId = UUID.randomUUID().toString();
  private final Map<String, NearCache<?, ?>> caches = new ConcurrentHashMap<>();

  public CacheInvalidationService() {
    this(null, null);
  }

  public CacheInvalidationService(@Nullable KafkaTemplate<String, String> kafkaTemplate) {
    this(kafkaTemplate, null);
  }

  @Autowired
  public CacheInvalidationService(
      @Nullable KafkaTemplate<String, String> kafkaTemplate, @Nullable ObjectMapper objectMapper) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper =
        objectMapper != null ? objectMapper : JsonMapper.builder().findAndAddModules().build();
  }

  public String getInstanceId() {
    return instanceId;
  }

  public void registerCache(NearCache<?, ?> cache) {
    caches.put(cache.getName(), cache);
  }

  public void publishInvalidation(String cacheName) {
    publishInvalidation(cacheName, null);
  }

  public void publishInvalidation(String cacheName, @Nullable String key) {
    CacheInvalidationEvent event =
        new CacheInvalidationEvent(cacheName, key, Instant.now(), instanceId);
    if (kafkaTemplate != null) {
      try {
        String payload = objectMapper.writeValueAsString(event);
        kafkaTemplate.send(TOPIC_CATALOG_CACHE_INVALIDATION, cacheName, payload);
        log.debug("Published cache invalidation event for cache {} key {}", cacheName, key);
      } catch (Exception e) {
        log.warn("Failed to publish cache invalidation event: {}", e.getMessage());
      }
    } else {
      // Local fallback
      handleInvalidation(event);
    }
  }

  @KafkaListener(
      topics = TOPIC_CATALOG_CACHE_INVALIDATION,
      groupId = "#{T(java.util.UUID).randomUUID().toString()}",
      autoStartup = "#{'${kafka.enabled:true}' == 'true'}")
  public void onInvalidationMessage(String message) {
    try {
      JsonNode root = objectMapper.readTree(message);
      JsonNode keyNode = root.get("key");
      JsonNode originInstanceIdNode = root.get("originInstanceId");
      CacheInvalidationEvent event =
          new CacheInvalidationEvent(
              root.get("cacheName").asString(),
              keyNode == null || keyNode.isNull() ? null : keyNode.asString(),
              Instant.now(),
              originInstanceIdNode != null ? originInstanceIdNode.asString() : null);
      handleInvalidation(event);
    } catch (Exception e) {
      log.warn("Failed to process cache invalidation message: {}", e.getMessage());
    }
  }

  public void handleInvalidation(CacheInvalidationEvent event) {
    NearCache<?, ?> cache = caches.get(event.cacheName());
    if (cache != null) {
      String key = event.key();
      if (key != null) {
        log.debug("Received invalidation for cache {} key {}", event.cacheName(), key);
        evictLocalKey(cache, key);
      } else {
        log.debug("Received clear all for cache {}", event.cacheName());
        cache.evictAllLocal();
      }
    }
  }

  // Extension helper for generic key type erasure
  @SuppressWarnings("unchecked")
  private static void evictLocalKey(NearCache<?, ?> cache, String key) {
    NearCache<Object, Object> typedCache = (NearCache<Object, Object>) cache;
    typedCache.getL1Cache().asMap().keySet().stream()
        .filter(k -> k.toString().equals(key))
        .findFirst()
        .ifPresent(typedCache::evictLocal);
  }
}
