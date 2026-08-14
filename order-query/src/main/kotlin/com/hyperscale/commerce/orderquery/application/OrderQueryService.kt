package com.hyperscale.commerce.orderquery.application

import com.hyperscale.commerce.orderquery.domain.OrderNotFoundException
import com.hyperscale.commerce.orderquery.jooq.order_query.Tables.ORDER_READ_MODEL
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

@Service
class OrderQueryService(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) {

  fun getOrder(id: Long): OrderDto {
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
    return row?.toDto() ?: throw OrderNotFoundException("Order with id $id not found")
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
    val ORDER_ITEMS_TYPE: TypeReference<List<OrderItemDto>> =
        object : TypeReference<List<OrderItemDto>>() {}
  }
}
