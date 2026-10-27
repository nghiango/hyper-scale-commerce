package com.hyperscale.commerce.config.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TransactionRoutingDataSourceTest {

  @AfterEach
  void cleanup() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  @DisplayName("returns PRIMARY for non-read-only or write transactions")
  void returnsPrimaryForNonReadOnlyOrWriteTransactions() {
    TransactionRoutingDataSource routingDataSource = new TransactionRoutingDataSource();

    // Default outside transaction
    Object key = routingDataSource.determineCurrentLookupKey();
    assertThat(key).isEqualTo(DataSourceType.PRIMARY);

    // Explicit readOnly = false
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    Object writeKey = routingDataSource.determineCurrentLookupKey();
    assertThat(writeKey).isEqualTo(DataSourceType.PRIMARY);
  }

  @Test
  @DisplayName("returns REPLICA for read-only transaction when lag is acceptable")
  void returnsReplicaForReadOnlyTransactionWhenLagIsAcceptable() {
    ReplicationLagTracker lagTracker = new ReplicationLagTracker(100L);
    lagTracker.recordLag(15L); // 15ms lag < 100ms
    TransactionRoutingDataSource routingDataSource = new TransactionRoutingDataSource(lagTracker);

    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

    Object key = routingDataSource.determineCurrentLookupKey();
    assertThat(key).isEqualTo(DataSourceType.REPLICA);
  }

  @Test
  @DisplayName(
      "falls back to PRIMARY for read-only transaction when replication lag exceeds threshold")
  void fallsBackToPrimaryForReadOnlyTransactionWhenReplicationLagExceedsThreshold() {
    ReplicationLagTracker lagTracker = new ReplicationLagTracker(100L);
    lagTracker.recordLag(250L); // 250ms lag > 100ms threshold
    TransactionRoutingDataSource routingDataSource = new TransactionRoutingDataSource(lagTracker);

    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

    Object key = routingDataSource.determineCurrentLookupKey();
    assertThat(key).isEqualTo(DataSourceType.PRIMARY);
  }

  @Test
  @DisplayName("falls back to PRIMARY when replica is marked unhealthy")
  void fallsBackToPrimaryWhenReplicaIsMarkedUnhealthy() {
    ReplicationLagTracker lagTracker = new ReplicationLagTracker();
    lagTracker.setReplicaHealthy(false);
    TransactionRoutingDataSource routingDataSource = new TransactionRoutingDataSource(lagTracker);

    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

    Object key = routingDataSource.determineCurrentLookupKey();
    assertThat(key).isEqualTo(DataSourceType.PRIMARY);
  }

  @Test
  @DisplayName("starts fenced until a successful lag sample")
  void startsFencedUntilASuccessfulLagSample() {
    assertThat(new ReplicationLagTracker().isLagAcceptable()).isFalse();
  }

  @Test
  @DisplayName("lag monitor records healthy samples and fences query failures")
  void lagMonitorRecordsHealthySamplesAndFencesQueryFailures() {
    JdbcOperations jdbc = mock(JdbcOperations.class);
    ReplicationLagTracker tracker = new ReplicationLagTracker();
    ReplicaLagMonitor monitor = new ReplicaLagMonitor(jdbc, tracker);
    when(jdbc.queryForObject(anyString(), eq(Double.class)))
        .thenReturn(25.0)
        .thenThrow(new IllegalStateException("replica unavailable"));

    monitor.sample();
    assertThat(tracker.isLagAcceptable()).isTrue();
    assertThat(tracker.getCurrentLagMs()).isEqualTo(25L);

    monitor.sample();
    assertThat(tracker.isLagAcceptable()).isFalse();
  }
}
