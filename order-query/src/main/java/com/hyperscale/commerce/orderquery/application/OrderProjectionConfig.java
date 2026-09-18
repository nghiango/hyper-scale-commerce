package com.hyperscale.commerce.orderquery.application;

import static com.hyperscale.commerce.orderquery.jooq.order_query.Tables.ORDER_READ_MODEL;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrderProjectionConfig {

  @Bean
  public Gauge orderReadModelLagGauge(DSLContext dsl, MeterRegistry meterRegistry) {
    return Gauge.builder(
            "order_read_model_lag_seconds",
            dsl,
            dslContext -> {
              Double lag =
                  dslContext
                      .select(
                          DSL.field(
                              "extract(epoch from (now() - {0}))",
                              Double.class, DSL.max(ORDER_READ_MODEL.UPDATED_AT)))
                      .from(ORDER_READ_MODEL)
                      .fetchOne(0, Double.class);
              return lag != null ? lag : 0.0;
            })
        .description("Seconds since the read model was last updated")
        .register(meterRegistry);
  }
}
