package com.hyperscale.commerce.config.datasource

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import javax.sql.DataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@EnableScheduling
class DataSourceRoutingConfig {
  @Bean("primaryDataSource")
  fun primaryDataSource(
      routingProperties: DataSourceRoutingProperties,
      environment: Environment,
      connectionDetailsProvider: ObjectProvider<JdbcConnectionDetails>,
      meterRegistry: MeterRegistry,
  ): DataSource {
    val connectionDetails = connectionDetailsProvider.getIfAvailable()
    val configuredUrl = routingProperties.primary.url
    val fallbackUrl =
        connectionDetails?.jdbcUrl ?: environment.getProperty("spring.datasource.url", "")
    val username =
        connectionDetails?.username ?: environment.getProperty("spring.datasource.username", "")
    val password =
        connectionDetails?.password ?: environment.getProperty("spring.datasource.password", "")
    return hikari(
            "primary",
            configuredUrl.ifBlank { fallbackUrl },
            username,
            password,
            routingProperties.primary.maximumPoolSize)
        .also { registerPoolGauge(meterRegistry, it, "primary") }
  }

  @Bean("replicaDataSource")
  fun replicaDataSource(
      routingProperties: DataSourceRoutingProperties,
      environment: Environment,
      connectionDetailsProvider: ObjectProvider<JdbcConnectionDetails>,
      meterRegistry: MeterRegistry,
  ): DataSource {
    val connectionDetails = connectionDetailsProvider.getIfAvailable()
    val configuredUrl = routingProperties.replica.url
    val fallbackUrl =
        connectionDetails?.jdbcUrl ?: environment.getProperty("spring.datasource.url", "")
    val username =
        connectionDetails?.username ?: environment.getProperty("spring.datasource.username", "")
    val password =
        connectionDetails?.password ?: environment.getProperty("spring.datasource.password", "")
    return hikari(
            "replica",
            configuredUrl.ifBlank { fallbackUrl },
            username,
            password,
            routingProperties.replica.maximumPoolSize)
        .also { registerPoolGauge(meterRegistry, it, "replica") }
  }

  @Bean
  fun replicationLagTracker(
      routingProperties: DataSourceRoutingProperties,
      meterRegistry: MeterRegistry,
  ): ReplicationLagTracker =
      ReplicationLagTracker(routingProperties.replica.maxLagMs).also { tracker ->
        Gauge.builder("postgres.replication.lag.seconds", tracker) {
              it.getCurrentLagMs() / 1_000.0
            }
            .tag("replica", "preferred-secondary")
            .register(meterRegistry)
      }

  @Bean
  fun replicaLagMonitor(
      @Qualifier("replicaDataSource") replicaDataSource: DataSource,
      lagTracker: ReplicationLagTracker,
  ): ReplicaLagMonitor = ReplicaLagMonitor(JdbcTemplate(replicaDataSource), lagTracker)

  @Bean("routingDataSource")
  fun routingDataSource(
      @Qualifier("primaryDataSource") primaryDataSource: DataSource,
      @Qualifier("replicaDataSource") replicaDataSource: DataSource,
      lagTracker: ReplicationLagTracker,
  ): TransactionRoutingDataSource =
      TransactionRoutingDataSource(lagTracker).apply {
        setTargetDataSources(
            mapOf(
                DataSourceType.PRIMARY to primaryDataSource,
                DataSourceType.REPLICA to replicaDataSource,
            ))
        setDefaultTargetDataSource(primaryDataSource)
        afterPropertiesSet()
      }

  @Bean
  @Primary
  fun dataSource(
      @Qualifier("routingDataSource") routingDataSource: TransactionRoutingDataSource,
  ): DataSource = LazyConnectionDataSourceProxy(routingDataSource)

  private fun hikari(
      poolName: String,
      url: String,
      username: String,
      password: String,
      maximumPoolSize: Int,
  ): HikariDataSource =
      HikariDataSource(
          HikariConfig().apply {
            jdbcUrl = url
            this.username = username
            this.password = password
            this.poolName = "hyperscale-$poolName"
            this.maximumPoolSize = maximumPoolSize
            minimumIdle = minOf(5, maximumPoolSize)
            connectionTimeout = 5_000
            idleTimeout = 600_000
            maxLifetime = 1_800_000
          })

  internal fun registerPoolGauge(
      meterRegistry: MeterRegistry,
      dataSource: HikariDataSource,
      pool: String,
  ) {
    Gauge.builder("datasource.connections.active", dataSource) {
          it.hikariPoolMXBean?.activeConnections?.toDouble() ?: 0.0
        }
        .tag("pool", pool)
        .register(meterRegistry)
  }
}
