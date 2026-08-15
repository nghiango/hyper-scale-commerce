package com.hyperscale.commerce.orderquery.config.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.TimeGauge
import io.micrometer.core.instrument.binder.MeterBinder
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import java.util.concurrent.TimeUnit
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private const val P95_PERCENTILE = 0.95

@Configuration
class SloMetricsConfiguration {

  @Bean
  fun sloMeterFilter(): MeterFilter =
      object : MeterFilter {
        override fun configure(
            id: Meter.Id,
            config: DistributionStatisticConfig,
        ): DistributionStatisticConfig {
          return if (id.name == "http.server.requests") {
            config.merge(DistributionStatisticConfig.builder().percentiles(P95_PERCENTILE).build())
          } else {
            config
          }
        }
      }

  @Bean fun sloMetrics(): MeterBinder = SloMetrics()
}

class SloMetrics : MeterBinder {
  private lateinit var registry: MeterRegistry

  override fun bindTo(registry: MeterRegistry) {
    this.registry = registry
    TimeGauge.builder("slo.get.order.by.id.p95", this, TimeUnit.SECONDS) { it.getOrderByIdP95() }
        .tag("slo", "p95")
        .register(registry)
    TimeGauge.builder("slo.get.orders.p95", this, TimeUnit.SECONDS) { it.getOrdersP95() }
        .tag("slo", "p95")
        .register(registry)
    Gauge.builder("slo.post.orders.success.rate", this) { it.getPostOrdersSuccessRate() }
        .tag("slo", "success.rate")
        .register(registry)
  }

  private fun getOrderByIdP95(): Double =
      registry
          .find("http.server.requests")
          .tag("method", "GET")
          .tag("uri", "/orders/{id}")
          .tag("outcome", "SUCCESS")
          .timer()
          ?.percentile(P95_PERCENTILE, TimeUnit.SECONDS) ?: 0.0

  private fun getOrdersP95(): Double =
      registry
          .find("http.server.requests")
          .tag("method", "GET")
          .tag("uri", "/orders")
          .tag("outcome", "SUCCESS")
          .timer()
          ?.percentile(P95_PERCENTILE, TimeUnit.SECONDS) ?: 0.0

  private fun getPostOrdersSuccessRate(): Double {
    val total =
        registry
            .find("http.server.requests")
            .tag("method", "POST")
            .tag("uri", "/orders")
            .meters()
            .filterIsInstance<io.micrometer.core.instrument.Timer>()
            .sumOf { it.count() }
    val success =
        registry
            .find("http.server.requests")
            .tag("method", "POST")
            .tag("uri", "/orders")
            .tag("outcome", "SUCCESS")
            .meters()
            .filterIsInstance<io.micrometer.core.instrument.Timer>()
            .sumOf { it.count() }
    return if (total == 0L) 0.0 else success.toDouble() / total
  }
}
