package com.hyperscale.commerce.orderquery.config.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SloMetricsConfiguration {
  static final double P95_PERCENTILE = 0.95;

  @Bean
  public MeterFilter sloMeterFilter() {
    return new MeterFilter() {
      @Override
      public DistributionStatisticConfig configure(
          Meter.Id id, DistributionStatisticConfig config) {
        if ("http.server.requests".equals(id.getName())) {
          return config.merge(
              DistributionStatisticConfig.builder().percentiles(P95_PERCENTILE).build());
        }
        return config;
      }
    };
  }

  @Bean
  public MeterBinder sloMetrics() {
    return new SloMetrics();
  }
}
