package com.hyperscale.commerce.orderquery.application

import com.hyperscale.commerce.orderquery.jooq.order_query.Tables.ORDER_READ_MODEL
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
                      "extract(epoch from (now() - {0}))",
                      Double::class.java,
                      DSL.max(ORDER_READ_MODEL.UPDATED_AT)))
              .from(ORDER_READ_MODEL)
              .fetchOne(0, Double::class.java) ?: 0.0
        }
        .description("Seconds since the read model was last updated")
        .register(meterRegistry)
  }
}
