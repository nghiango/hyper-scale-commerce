package com.hyperscale.commerce.modules.inventory.domain

interface ReservationRepository {

  fun recordIfAbsent(orderId: Long, sku: String, quantity: Int, eventId: String): Boolean

  fun countByEventId(eventId: String): Long
}
