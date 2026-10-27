package com.hyperscale.commerce.modules.inventory.infrastructure;

import com.hyperscale.commerce.jooq.inventory.Tables;
import com.hyperscale.commerce.modules.inventory.domain.ReservationRepository;
import com.hyperscale.commerce.modules.inventory.domain.ReservationStatus;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class SpringDataJdbcReservationRepository implements ReservationRepository {
  private final ReservationJdbcRepository reservationJdbcRepository;
  private final DSLContext dsl;

  public SpringDataJdbcReservationRepository(
      ReservationJdbcRepository reservationJdbcRepository, DSLContext dsl) {
    this.reservationJdbcRepository = reservationJdbcRepository;
    this.dsl = dsl;
  }

  @Override
  public boolean recordIfAbsent(long orderId, String sku, int quantity, String eventId) {
    int inserted =
        dsl.insertInto(
                Tables.RESERVATIONS,
                Tables.RESERVATIONS.ORDER_ID,
                Tables.RESERVATIONS.SKU,
                Tables.RESERVATIONS.QUANTITY,
                Tables.RESERVATIONS.STATUS,
                Tables.RESERVATIONS.EVENT_ID)
            .values(orderId, sku, quantity, ReservationStatus.RESERVED.name(), eventId)
            .onConflict(Tables.RESERVATIONS.EVENT_ID, Tables.RESERVATIONS.SKU)
            .doNothing()
            .execute();
    return inserted > 0;
  }

  @Override
  public long countByEventId(String eventId) {
    return reservationJdbcRepository.countByEventId(eventId);
  }
}
