@file:Suppress("TooGenericExceptionCaught")

package com.hyperscale.commerce.orderquery.config.cache

import com.hyperscale.commerce.contracts.CacheInvalidationEvent
import com.hyperscale.commerce.orderquery.application.OrderQueryService
import com.hyperscale.commerce.orderquery.application.cache.CacheInvalidationPublisher
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class KafkaCacheInvalidationPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) : CacheInvalidationPublisher {
  private val logger = LoggerFactory.getLogger(KafkaCacheInvalidationPublisher::class.java)
  private val instanceId = UUID.randomUUID().toString()

  override fun publish(cacheName: String, key: String?) {
    val event =
        CacheInvalidationEvent(
            cacheName = cacheName,
            key = key,
            originInstanceId = instanceId,
        )
    try {
      val payload = objectMapper.writeValueAsString(event)
      kafkaTemplate.send(
          CacheInvalidationListener.TOPIC_ORDER_CACHE_INVALIDATION,
          key ?: cacheName,
          payload,
      )
    } catch (exception: Exception) {
      logger.warn("Failed to publish cache invalidation for {}: {}", key, exception.message)
    }
  }
}

@Service
class CacheInvalidationListener(
    private val objectMapper: ObjectMapper,
    private val orderQueryService: OrderQueryService,
) {
  private val logger = LoggerFactory.getLogger(CacheInvalidationListener::class.java)

  @KafkaListener(
      topics = [TOPIC_ORDER_CACHE_INVALIDATION],
      groupId = "#{T(java.util.UUID).randomUUID().toString()}",
      autoStartup = "#{'\${kafka.enabled:true}' == 'true'}",
  )
  fun onInvalidation(message: String) {
    try {
      val event = objectMapper.readTree(message)
      if (event.get("cacheName")?.asString() != OrderQueryService.ORDER_CACHE_NAME) return

      val keyNode = event.get("key")
      val key = if (keyNode == null || keyNode.isNull) null else keyNode.asString()
      if (key == null) {
        orderQueryService.evictAllLocal()
      } else {
        key.toLongOrNull()?.let(orderQueryService::evictLocal)
      }
    } catch (exception: Exception) {
      logger.warn("Failed to apply cache invalidation: {}", exception.message)
    }
  }

  companion object {
    const val TOPIC_ORDER_CACHE_INVALIDATION = "order-cache-evict"
  }
}
