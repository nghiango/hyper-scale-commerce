package com.hyperscale.commerce.modules.inventory.infrastructure

import com.hyperscale.commerce.jooq.inventory.Tables.RESERVATIONS
import com.hyperscale.commerce.modules.inventory.domain.ReservationRepository
import com.hyperscale.commerce.modules.inventory.domain.ReservationStatus
import org.jooq.DSLContext
import org.springframework.stereotype.Repository

@Repository
class SpringDataJdbcReservationRepository(
    private val reservationJdbcRepository: ReservationJdbcRepository,
    private val dsl: DSLContext,
) : ReservationRepository {

  override fun recordIfAbsent(orderId: Long, sku: String, quantity: Int, eventId: String): Boolean {
    val inserted =
        dsl.insertInto(
                RESERVATIONS,
                RESERVATIONS.ORDER_ID,
                RESERVATIONS.SKU,
                RESERVATIONS.QUANTITY,
                RESERVATIONS.STATUS,
                RESERVATIONS.EVENT_ID,
            )
            .values(orderId, sku, quantity, ReservationStatus.RESERVED.name, eventId)
            .onConflict(RESERVATIONS.EVENT_ID, RESERVATIONS.SKU)
            .doNothing()
            .execute()
    return inserted > 0
  }

  override fun countByEventId(eventId: String): Long =
      reservationJdbcRepository.countByEventId(eventId)
}
