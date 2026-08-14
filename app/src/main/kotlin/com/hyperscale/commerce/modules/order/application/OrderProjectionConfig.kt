package com.hyperscale.commerce.modules.order.application

import com.hyperscale.commerce.jooq.order.Tables.ORDERS
import com.hyperscale.commerce.jooq.order.Tables.ORDER_READ_MODEL
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OrderProjectionConfig {

  @Bean
  fun orderReadModelLagGauge(dsl: DSLContext, meterRegistry: MeterRegistry): Gauge {
    return Gauge.builder("order_read_model_lag_seconds", dsl) { dslContext ->
          dslContext
              .select(
                  DSL.field(
                      "extract(epoch from (now() - {0}))", Double::class.java, ORDERS.CREATED_AT))
              .from(ORDERS)
              .whereNotExists(
                  dslContext
                      .selectOne()
                      .from(ORDER_READ_MODEL)
                      .where(ORDER_READ_MODEL.ORDER_ID.eq(ORDERS.ID)))
              .orderBy(ORDERS.CREATED_AT)
              .limit(1)
              .fetchOne(0, Double::class.java) ?: 0.0
        }
        .description("Seconds since the oldest order not yet projected to the read model")
        .register(meterRegistry)
  }
}
