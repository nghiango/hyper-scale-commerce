package com.hyperscale.commerce.modules.order.infrastructure;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface OrderJdbcRepository extends CrudRepository<OrderEntity, Long> {

  @Modifying
  @Query("UPDATE \"order\".orders SET status = :status WHERE id = :id")
  int updateStatus(@Param("id") long id, @Param("status") String status);
}
