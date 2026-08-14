package com.hyperscale.commerce.modules.inventory.infrastructure

import org.springframework.data.repository.CrudRepository

interface ReservationJdbcRepository : CrudRepository<ReservationEntity, Long> {
  fun countByEventId(eventId: String): Long
}
