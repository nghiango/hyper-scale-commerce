package com.hyperscale.commerce.config.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DataSourceMetricsTest {

  @Test
  @DisplayName("registers primary and replica pool gauges")
  void registersPrimaryAndReplicaPoolGauges() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DataSourceRoutingConfig config = new DataSourceRoutingConfig();
    config.registerPoolGauge(registry, mock(HikariDataSource.class), "primary");
    config.registerPoolGauge(registry, mock(HikariDataSource.class), "replica");

    assertThat(registry.find("datasource.connections.active").tag("pool", "primary").gauge())
        .isNotNull();
    assertThat(registry.find("datasource.connections.active").tag("pool", "replica").gauge())
        .isNotNull();
  }

  @Test
  @DisplayName("publishes replica lag in seconds")
  void publishesReplicaLagInSeconds() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ReplicationLagTracker tracker =
        new DataSourceRoutingConfig()
            .replicationLagTracker(new DataSourceRoutingProperties(null, null), registry);

    tracker.recordLag(250L);

    Gauge gauge =
        registry
            .find("postgres.replication.lag.seconds")
            .tag("replica", "preferred-secondary")
            .gauge();
    assertThat(gauge != null ? gauge.value() : null).isEqualTo(0.25);
  }
}
