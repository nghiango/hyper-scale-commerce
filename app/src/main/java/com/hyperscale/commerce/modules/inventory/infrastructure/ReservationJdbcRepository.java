package com.hyperscale.commerce.modules.inventory.infrastructure;

import org.springframework.data.repository.CrudRepository;

public interface ReservationJdbcRepository extends CrudRepository<ReservationEntity, Long> {
  long countByEventId(String eventId);
}
