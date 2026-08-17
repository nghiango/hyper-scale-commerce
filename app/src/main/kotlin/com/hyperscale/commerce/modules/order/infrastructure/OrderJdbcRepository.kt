package com.hyperscale.commerce.modules.order.infrastructure

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param

interface OrderJdbcRepository : CrudRepository<OrderEntity, Long> {

  @Modifying
  @Query("UPDATE \"order\".orders SET status = :status WHERE id = :id")
  fun updateStatus(@Param("id") id: Long, @Param("status") status: String): Int
}
