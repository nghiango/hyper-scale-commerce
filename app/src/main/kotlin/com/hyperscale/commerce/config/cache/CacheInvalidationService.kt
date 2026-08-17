@file:Suppress("TooGenericExceptionCaught")

package com.hyperscale.commerce.config.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.hyperscale.commerce.contracts.CacheInvalidationEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service

@Service
class CacheInvalidationService(
    @Autowired(required = false) private val kafkaTemplate: KafkaTemplate<String, String>? = null,
    private val objectMapper: ObjectMapper = ObjectMapper().findAndRegisterModules(),
) {
  private val log = LoggerFactory.getLogger(CacheInvalidationService::class.java)
  val instanceId: String = UUID.randomUUID().toString()
  private val caches = ConcurrentHashMap<String, NearCache<*, *>>()

  fun registerCache(cache: NearCache<*, *>) {
    caches[cache.name] = cache
  }

  fun publishInvalidation(cacheName: String, key: String? = null) {
    val event =
        CacheInvalidationEvent(cacheName = cacheName, key = key, originInstanceId = instanceId)
    if (kafkaTemplate != null) {
      try {
        val payload = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(TOPIC_CATALOG_CACHE_INVALIDATION, cacheName, payload)
        log.debug("Published cache invalidation event for cache {} key {}", cacheName, key)
      } catch (e: Exception) {
        log.warn("Failed to publish cache invalidation event: {}", e.message)
      }
    } else {
      // Local fallback
      handleInvalidation(event)
    }
  }

  @KafkaListener(
      topics = [TOPIC_CATALOG_CACHE_INVALIDATION],
      groupId = "#{T(java.util.UUID).randomUUID().toString()}",
      autoStartup = "#{'\${kafka.enabled:true}' == 'true'}")
  fun onInvalidationMessage(message: String) {
    try {
      val root = objectMapper.readTree(message)
      val keyNode = root.get("key")
      val event =
          CacheInvalidationEvent(
              cacheName = root.get("cacheName").asText(),
              key = if (keyNode == null || keyNode.isNull) null else keyNode.asText(),
              originInstanceId = root.get("originInstanceId")?.asText(),
          )
      handleInvalidation(event)
    } catch (e: Exception) {
      log.warn("Failed to process cache invalidation message: {}", e.message)
    }
  }

  fun handleInvalidation(event: CacheInvalidationEvent) {
    val cache = caches[event.cacheName]
    if (cache != null) {
      val key = event.key
      if (key != null) {
        log.debug("Received invalidation for cache {} key {}", event.cacheName, key)
        cache.evictLocalKey(key)
      } else {
        log.debug("Received clear all for cache {}", event.cacheName)
        cache.evictAllLocal()
      }
    }
  }

  companion object {
    const val TOPIC_CATALOG_CACHE_INVALIDATION = "catalog-cache-evict"
  }
}

// Extension helper for generic key type erasure
fun NearCache<*, *>.evictLocalKey(key: String) {
  @Suppress("UNCHECKED_CAST")
  (this as NearCache<Any, Any>)
      .l1Cache
      .asMap()
      .keys
      .firstOrNull { it.toString() == key }
      ?.let { this.evictLocal(it) }
}
