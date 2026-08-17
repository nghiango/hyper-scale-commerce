package com.hyperscale.commerce.orderquery.application

import com.hyperscale.commerce.orderquery.application.cache.CacheInvalidationPublisher
import com.hyperscale.commerce.orderquery.application.cache.L2CacheStore
import com.hyperscale.commerce.orderquery.application.cache.NearCache
import com.hyperscale.commerce.orderquery.domain.OrderNotFoundException
import com.hyperscale.commerce.orderquery.jooq.order_query.Tables.ORDER_READ_MODEL
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

@Service
class OrderQueryService(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
    @Autowired(required = false) meterRegistry: MeterRegistry? = null,
    private val l2CacheStore: L2CacheStore,
    private val invalidationPublisher: CacheInvalidationPublisher =
        CacheInvalidationPublisher { _, _ ->
        },
) {

  private val orderCache: NearCache<Long, OrderDto> =
      NearCache(
          name = ORDER_CACHE_NAME,
          l1MaxSize = ORDER_CACHE_MAX_SIZE,
          l1Ttl = Duration.ofSeconds(ORDER_CACHE_TTL_SECONDS),
          l2Ttl = Duration.ofMinutes(ORDER_CACHE_L2_TTL_MINUTES),
          l2Store = l2CacheStore,
          valueClass = OrderDto::class.java,
          objectMapper = objectMapper,
          meterRegistry = meterRegistry,
      )

  @Transactional(readOnly = true)
  fun getOrder(id: Long): OrderDto {
    val cached =
        orderCache.get(id) {
          val row =
              dsl.select(
                      ORDER_READ_MODEL.ORDER_ID,
                      ORDER_READ_MODEL.STATUS,
                      ORDER_READ_MODEL.ITEMS,
                      ORDER_READ_MODEL.CREATED_AT,
                  )
                  .from(ORDER_READ_MODEL)
                  .where(ORDER_READ_MODEL.ORDER_ID.eq(id))
                  .fetchOne()
          row?.toDto()
        }

    return cached ?: throw OrderNotFoundException("Order with id $id not found")
  }

  fun evictOrder(id: Long) {
    orderCache.evict(id)
    invalidationPublisher.publish(ORDER_CACHE_NAME, id.toString())
  }

  fun evictAll() {
    orderCache.evictAll()
    invalidationPublisher.publish(ORDER_CACHE_NAME, null)
  }

  fun evictLocal(id: Long) {
    orderCache.evictLocal(id)
  }

  fun evictAllLocal() {
    orderCache.evictAllLocal()
  }

  @Transactional(readOnly = true)
  fun listOrders(page: Int, size: Int): PagedOrdersDto {
    require(page >= MIN_PAGE) { "Page must not be negative" }
    require(size in MIN_SIZE..MAX_SIZE) { "Size must be between $MIN_SIZE and $MAX_SIZE" }
    val total = dsl.selectCount().from(ORDER_READ_MODEL).fetchOne(0, Long::class.java) ?: 0L
    val orders =
        dsl.select(
                ORDER_READ_MODEL.ORDER_ID,
                ORDER_READ_MODEL.STATUS,
                ORDER_READ_MODEL.ITEMS,
                ORDER_READ_MODEL.CREATED_AT,
            )
            .from(ORDER_READ_MODEL)
            .orderBy(ORDER_READ_MODEL.CREATED_AT.desc(), ORDER_READ_MODEL.ORDER_ID.desc())
            .limit(size)
            .offset(page * size)
            .fetch { record -> record.toDto() }
    return PagedOrdersDto(total = total, items = orders)
  }

  private fun Record.toDto(): OrderDto {
    val items = objectMapper.readValue(getValue(ORDER_READ_MODEL.ITEMS).data(), ORDER_ITEMS_TYPE)
    return OrderDto(
        id = getValue(ORDER_READ_MODEL.ORDER_ID),
        status = getValue(ORDER_READ_MODEL.STATUS),
        items = items,
        createdAt = getValue(ORDER_READ_MODEL.CREATED_AT).toInstant(),
    )
  }

  companion object {
    const val MIN_PAGE = 0
    const val MIN_SIZE = 1
    const val MAX_SIZE = 100
    const val ORDER_CACHE_MAX_SIZE = 50_000L
    const val ORDER_CACHE_TTL_SECONDS = 30L
    const val ORDER_CACHE_L2_TTL_MINUTES = 10L
    const val ORDER_CACHE_NAME = "order_query"
    val ORDER_ITEMS_TYPE: TypeReference<List<OrderItemDto>> =
        object : TypeReference<List<OrderItemDto>>() {}
  }
}
