package com.hyperscale.commerce.config.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class DataSourceRoutingConfig {

  @Bean("primaryDataSource")
  public DataSource primaryDataSource(
      DataSourceRoutingProperties routingProperties,
      Environment environment,
      ObjectProvider<JdbcConnectionDetails> connectionDetailsProvider,
      MeterRegistry meterRegistry) {
    JdbcConnectionDetails connectionDetails = connectionDetailsProvider.getIfAvailable();
    String configuredUrl = routingProperties.primary().url();
    String fallbackUrl =
        connectionDetails != null && connectionDetails.getJdbcUrl() != null
            ? connectionDetails.getJdbcUrl()
            : environment.getProperty("spring.datasource.url", "");
    String username =
        connectionDetails != null && connectionDetails.getUsername() != null
            ? connectionDetails.getUsername()
            : environment.getProperty("spring.datasource.username", "");
    String password =
        connectionDetails != null && connectionDetails.getPassword() != null
            ? connectionDetails.getPassword()
            : environment.getProperty("spring.datasource.password", "");
    HikariDataSource dataSource =
        hikari(
            "primary",
            configuredUrl.isBlank() ? fallbackUrl : configuredUrl,
            username,
            password,
            routingProperties.primary().maximumPoolSize());
    registerPoolGauge(meterRegistry, dataSource, "primary");
    return dataSource;
  }

  @Bean("replicaDataSource")
  public DataSource replicaDataSource(
      DataSourceRoutingProperties routingProperties,
      Environment environment,
      ObjectProvider<JdbcConnectionDetails> connectionDetailsProvider,
      MeterRegistry meterRegistry) {
    JdbcConnectionDetails connectionDetails = connectionDetailsProvider.getIfAvailable();
    String configuredUrl = routingProperties.replica().url();
    String fallbackUrl =
        connectionDetails != null && connectionDetails.getJdbcUrl() != null
            ? connectionDetails.getJdbcUrl()
            : environment.getProperty("spring.datasource.url", "");
    String username =
        connectionDetails != null && connectionDetails.getUsername() != null
            ? connectionDetails.getUsername()
            : environment.getProperty("spring.datasource.username", "");
    String password =
        connectionDetails != null && connectionDetails.getPassword() != null
            ? connectionDetails.getPassword()
            : environment.getProperty("spring.datasource.password", "");
    HikariDataSource dataSource =
        hikari(
            "replica",
            configuredUrl.isBlank() ? fallbackUrl : configuredUrl,
            username,
            password,
            routingProperties.replica().maximumPoolSize());
    registerPoolGauge(meterRegistry, dataSource, "replica");
    return dataSource;
  }

  @Bean
  public ReplicationLagTracker replicationLagTracker(
      DataSourceRoutingProperties routingProperties, MeterRegistry meterRegistry) {
    ReplicationLagTracker tracker =
        new ReplicationLagTracker(routingProperties.replica().maxLagMs());
    Gauge.builder("postgres.replication.lag.seconds", tracker, t -> t.getCurrentLagMs() / 1_000.0)
        .tag("replica", "preferred-secondary")
        .register(meterRegistry);
    return tracker;
  }

  @Bean
  public ReplicaLagMonitor replicaLagMonitor(
      @Qualifier("replicaDataSource") DataSource replicaDataSource,
      ReplicationLagTracker lagTracker) {
    return new ReplicaLagMonitor(new JdbcTemplate(replicaDataSource), lagTracker);
  }

  @Bean("routingDataSource")
  public TransactionRoutingDataSource routingDataSource(
      @Qualifier("primaryDataSource") DataSource primaryDataSource,
      @Qualifier("replicaDataSource") DataSource replicaDataSource,
      ReplicationLagTracker lagTracker) {
    TransactionRoutingDataSource routingDataSource = new TransactionRoutingDataSource(lagTracker);
    routingDataSource.setTargetDataSources(
        Map.of(
            DataSourceType.PRIMARY, primaryDataSource, DataSourceType.REPLICA, replicaDataSource));
    routingDataSource.setDefaultTargetDataSource(primaryDataSource);
    routingDataSource.afterPropertiesSet();
    return routingDataSource;
  }

  @Bean
  @Primary
  public DataSource dataSource(
      @Qualifier("routingDataSource") TransactionRoutingDataSource routingDataSource) {
    return new LazyConnectionDataSourceProxy(routingDataSource);
  }

  private HikariDataSource hikari(
      String poolName, String url, String username, String password, int maximumPoolSize) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(username);
    config.setPassword(password);
    config.setPoolName("hyperscale-" + poolName);
    config.setMaximumPoolSize(maximumPoolSize);
    config.setMinimumIdle(Math.min(5, maximumPoolSize));
    config.setConnectionTimeout(5_000);
    config.setIdleTimeout(600_000);
    config.setMaxLifetime(1_800_000);
    return new HikariDataSource(config);
  }

  public void registerPoolGauge(
      MeterRegistry meterRegistry, HikariDataSource dataSource, String pool) {
    Gauge.builder(
            "datasource.connections.active",
            dataSource,
            ds ->
                ds.getHikariPoolMXBean() != null
                    ? ds.getHikariPoolMXBean().getActiveConnections()
                    : 0.0)
        .tag("pool", pool)
        .register(meterRegistry);
  }
}
