package com.hyperscale.commerce.orderquery.application

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.hyperscale.commerce.orderquery.domain.OrderNotFoundException
import com.hyperscale.commerce.orderquery.jooq.order_query.Tables.ORDER_READ_MODEL
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import java.time.Duration
import java.util.Optional
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

@Service
class OrderQueryService(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
    @Autowired(required = false) meterRegistry: MeterRegistry? = null,
) {

  private val orderCache: Cache<Long, Optional<OrderDto>> =
      Caffeine.newBuilder()
          .maximumSize(ORDER_CACHE_MAX_SIZE)
          .expireAfterWrite(Duration.ofSeconds(ORDER_CACHE_TTL_SECONDS))
          .recordStats()
          .build()

  init {
    if (meterRegistry != null) {
      CaffeineCacheMetrics.monitor(meterRegistry, orderCache, "order_query")
    }
  }

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
          Optional.ofNullable(row?.toDto())
        }

    return cached?.orElse(null) ?: throw OrderNotFoundException("Order with id $id not found")
  }

  fun evictOrder(id: Long) {
    orderCache.invalidate(id)
  }

  fun evictAll() {
    orderCache.invalidateAll()
  }

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

  private companion object {
    const val MIN_PAGE = 0
    const val MIN_SIZE = 1
    const val MAX_SIZE = 100
    const val ORDER_CACHE_MAX_SIZE = 50_000L
    const val ORDER_CACHE_TTL_SECONDS = 30L
    val ORDER_ITEMS_TYPE: TypeReference<List<OrderItemDto>> =
        object : TypeReference<List<OrderItemDto>>() {}
  }
}
