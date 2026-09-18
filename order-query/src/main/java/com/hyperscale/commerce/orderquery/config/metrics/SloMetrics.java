package com.hyperscale.commerce.orderquery.config.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.util.concurrent.TimeUnit;

public class SloMetrics implements MeterBinder {
  private MeterRegistry registry;

  @Override
  public void bindTo(MeterRegistry registry) {
    this.registry = registry;
    TimeGauge.builder("slo.get.order.by.id.p95", this, TimeUnit.SECONDS, it -> it.getOrderByIdP95())
        .tag("slo", "p95")
        .register(registry);
    TimeGauge.builder("slo.get.orders.p95", this, TimeUnit.SECONDS, it -> it.getOrdersP95())
        .tag("slo", "p95")
        .register(registry);
    Gauge.builder("slo.post.orders.success.rate", this, it -> it.getPostOrdersSuccessRate())
        .tag("slo", "success.rate")
        .register(registry);
  }

  private double getOrderByIdP95() {
    Timer timer =
        registry
            .find("http.server.requests")
            .tag("method", "GET")
            .tag("uri", "/orders/{id}")
            .tag("outcome", "SUCCESS")
            .timer();
    if (timer == null) {
      return 0.0;
    }
    for (ValueAtPercentile percentile : timer.takeSnapshot().percentileValues()) {
      if (percentile.percentile() == SloMetricsConfiguration.P95_PERCENTILE) {
        return percentile.value(TimeUnit.SECONDS);
      }
    }
    return 0.0;
  }

  private double getOrdersP95() {
    Timer timer =
        registry
            .find("http.server.requests")
            .tag("method", "GET")
            .tag("uri", "/orders")
            .tag("outcome", "SUCCESS")
            .timer();
    if (timer == null) {
      return 0.0;
    }
    for (ValueAtPercentile percentile : timer.takeSnapshot().percentileValues()) {
      if (percentile.percentile() == SloMetricsConfiguration.P95_PERCENTILE) {
        return percentile.value(TimeUnit.SECONDS);
      }
    }
    return 0.0;
  }

  private double getPostOrdersSuccessRate() {
    long total =
        registry
            .find("http.server.requests")
            .tag("method", "POST")
            .tag("uri", "/orders")
            .meters()
            .stream()
            .filter(Timer.class::isInstance)
            .mapToLong(meter -> ((Timer) meter).count())
            .sum();
    long success =
        registry
            .find("http.server.requests")
            .tag("method", "POST")
            .tag("uri", "/orders")
            .tag("outcome", "SUCCESS")
            .meters()
            .stream()
            .filter(Timer.class::isInstance)
            .mapToLong(meter -> ((Timer) meter).count())
            .sum();
    return total == 0L ? 0.0 : (double) success / total;
  }
}
