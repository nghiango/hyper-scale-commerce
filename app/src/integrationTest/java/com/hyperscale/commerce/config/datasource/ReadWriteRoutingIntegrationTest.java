package com.hyperscale.commerce.config.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
public class ReadWriteRoutingIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> primary =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

  @Container
  static final PostgreSQLContainer<?> replica =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

  private static HikariDataSource primaryDataSource;
  private static HikariDataSource replicaDataSource;
  private static ReplicationLagTracker lagTracker;
  private static TransactionTemplate transaction;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void configureRouting() {
    primaryDataSource = dataSource(primary, "routing-primary");
    replicaDataSource = dataSource(replica, "routing-replica");
    new JdbcTemplate(primaryDataSource)
        .execute("CREATE TABLE routing_marker (value TEXT NOT NULL)");
    new JdbcTemplate(primaryDataSource).update("INSERT INTO routing_marker VALUES ('PRIMARY')");
    new JdbcTemplate(replicaDataSource)
        .execute("CREATE TABLE routing_marker (value TEXT NOT NULL)");
    new JdbcTemplate(replicaDataSource).update("INSERT INTO routing_marker VALUES ('REPLICA')");

    lagTracker = new ReplicationLagTracker(100);
    TransactionRoutingDataSource router = new TransactionRoutingDataSource(lagTracker);
    router.setTargetDataSources(
        Map.of(
            DataSourceType.PRIMARY, primaryDataSource,
            DataSourceType.REPLICA, replicaDataSource));
    router.setDefaultTargetDataSource(primaryDataSource);
    router.afterPropertiesSet();
    LazyConnectionDataSourceProxy lazyDataSource = new LazyConnectionDataSourceProxy(router);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(lazyDataSource));
    jdbc = new JdbcTemplate(lazyDataSource);
  }

  @AfterAll
  static void closePools() {
    primaryDataSource.close();
    replicaDataSource.close();
  }

  private static HikariDataSource dataSource(PostgreSQLContainer<?> container, String poolName) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(container.getJdbcUrl());
    config.setUsername(container.getUsername());
    config.setPassword(container.getPassword());
    config.setPoolName(poolName);
    config.setMaximumPoolSize(2);
    return new HikariDataSource(config);
  }

  @Test
  @DisplayName("write transactions use primary")
  void writeTransactionsUsePrimary() {
    transaction.setReadOnly(false);

    assertThat((String) transaction.execute(status -> marker())).isEqualTo("PRIMARY");
  }

  @Test
  @DisplayName("read-only transactions use healthy replica")
  void readOnlyTransactionsUseHealthyReplica() {
    lagTracker.recordLag(10);
    transaction.setReadOnly(true);

    assertThat((String) transaction.execute(status -> marker())).isEqualTo("REPLICA");
  }

  @Test
  @DisplayName("read-only transactions fall back to primary when replica lag is high")
  void readOnlyTransactionsFallBackToPrimaryWhenReplicaLagIsHigh() {
    lagTracker.recordLag(101);
    transaction.setReadOnly(true);

    assertThat((String) transaction.execute(status -> marker())).isEqualTo("PRIMARY");
  }

  private static String marker() {
    return Objects.requireNonNull(
        jdbc.queryForObject("SELECT value FROM routing_marker", String.class));
  }
}
