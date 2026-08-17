package com.hyperscale.commerce.config.datasource

import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class DataSourceMetricsTest {
  @Test
  fun `registers primary and replica pool gauges`() {
    val registry = SimpleMeterRegistry()
    val config = DataSourceRoutingConfig()
    config.registerPoolGauge(registry, mock(HikariDataSource::class.java), "primary")
    config.registerPoolGauge(registry, mock(HikariDataSource::class.java), "replica")

    assertThat(registry.find("datasource.connections.active").tag("pool", "primary").gauge())
        .isNotNull()
    assertThat(registry.find("datasource.connections.active").tag("pool", "replica").gauge())
        .isNotNull()
  }

  @Test
  fun `publishes replica lag in seconds`() {
    val registry = SimpleMeterRegistry()
    val tracker = DataSourceRoutingConfig().replicationLagTracker(100, registry)

    tracker.recordLag(250)

    assertThat(
            registry
                .find("postgres.replication.lag.seconds")
                .tag("replica", "preferred-secondary")
                .gauge()
                ?.value())
        .isEqualTo(0.25)
  }
}
