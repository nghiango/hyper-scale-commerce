package com.hyperscale.commerce.orderquery.config.cache;

import com.hyperscale.commerce.orderquery.application.OrderQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class CacheInvalidationListener {
  private static final Logger logger = LoggerFactory.getLogger(CacheInvalidationListener.class);

  public static final String TOPIC_ORDER_CACHE_INVALIDATION = "order-cache-evict";

  private final ObjectMapper objectMapper;
  private final OrderQueryService orderQueryService;

  public CacheInvalidationListener(ObjectMapper objectMapper, OrderQueryService orderQueryService) {
    this.objectMapper = objectMapper;
    this.orderQueryService = orderQueryService;
  }

  @KafkaListener(
      topics = {TOPIC_ORDER_CACHE_INVALIDATION},
      groupId = "#{T(java.util.UUID).randomUUID().toString()}",
      autoStartup = "#{'${kafka.enabled:true}' == 'true'}")
  public void onInvalidation(String message) {
    try {
      JsonNode event = objectMapper.readTree(message);
      JsonNode cacheNameNode = event.get("cacheName");
      String cacheName = cacheNameNode != null ? cacheNameNode.asString() : null;
      if (!OrderQueryService.ORDER_CACHE_NAME.equals(cacheName)) {
        return;
      }

      JsonNode keyNode = event.get("key");
      String key = (keyNode == null || keyNode.isNull()) ? null : keyNode.asString();
      if (key == null) {
        orderQueryService.evictAllLocal();
      } else {
        Long parsedKey = toLongOrNull(key);
        if (parsedKey != null) {
          orderQueryService.evictLocal(parsedKey);
        }
      }
    } catch (Exception exception) {
      logger.warn("Failed to apply cache invalidation: {}", exception.getMessage());
    }
  }

  private static Long toLongOrNull(String value) {
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
