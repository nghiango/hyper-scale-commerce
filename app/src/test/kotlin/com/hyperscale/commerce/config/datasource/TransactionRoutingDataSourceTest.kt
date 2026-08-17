package com.hyperscale.commerce.config.datasource

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.transaction.support.TransactionSynchronizationManager

class TransactionRoutingDataSourceTest {

  @AfterEach
  fun cleanup() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization()
    }
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
  }

  @Test
  fun `returns PRIMARY for non-read-only or write transactions`() {
    val routingDataSource = TransactionRoutingDataSource()

    // Default outside transaction
    val key = routingDataSource.determineCurrentLookupKey()
    assertThat(key).isEqualTo(DataSourceType.PRIMARY)

    // Explicit readOnly = false
    TransactionSynchronizationManager.initSynchronization()
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
    val writeKey = routingDataSource.determineCurrentLookupKey()
    assertThat(writeKey).isEqualTo(DataSourceType.PRIMARY)
  }

  @Test
  fun `returns REPLICA for read-only transaction when lag is acceptable`() {
    val lagTracker = ReplicationLagTracker(maxAllowableLagMs = 100L)
    lagTracker.recordLag(15L) // 15ms lag < 100ms
    val routingDataSource = TransactionRoutingDataSource(lagTracker)

    TransactionSynchronizationManager.initSynchronization()
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)

    val key = routingDataSource.determineCurrentLookupKey()
    assertThat(key).isEqualTo(DataSourceType.REPLICA)
  }

  @Test
  fun `falls back to PRIMARY for read-only transaction when replication lag exceeds threshold`() {
    val lagTracker = ReplicationLagTracker(maxAllowableLagMs = 100L)
    lagTracker.recordLag(250L) // 250ms lag > 100ms threshold
    val routingDataSource = TransactionRoutingDataSource(lagTracker)

    TransactionSynchronizationManager.initSynchronization()
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)

    val key = routingDataSource.determineCurrentLookupKey()
    assertThat(key).isEqualTo(DataSourceType.PRIMARY)
  }

  @Test
  fun `falls back to PRIMARY when replica is marked unhealthy`() {
    val lagTracker = ReplicationLagTracker()
    lagTracker.setReplicaHealthy(false)
    val routingDataSource = TransactionRoutingDataSource(lagTracker)

    TransactionSynchronizationManager.initSynchronization()
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)

    val key = routingDataSource.determineCurrentLookupKey()
    assertThat(key).isEqualTo(DataSourceType.PRIMARY)
  }

  @Test
  fun `starts fenced until a successful lag sample`() {
    assertThat(ReplicationLagTracker().isLagAcceptable()).isFalse()
  }

  @Test
  fun `lag monitor records healthy samples and fences query failures`() {
    val jdbc = mock(JdbcOperations::class.java)
    val tracker = ReplicationLagTracker()
    val monitor = ReplicaLagMonitor(jdbc, tracker)
    `when`(
            jdbc.queryForObject(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Double::class.java)))
        .thenReturn(25.0)
        .thenThrow(IllegalStateException("replica unavailable"))

    monitor.sample()
    assertThat(tracker.isLagAcceptable()).isTrue()
    assertThat(tracker.getCurrentLagMs()).isEqualTo(25L)

    monitor.sample()
    assertThat(tracker.isLagAcceptable()).isFalse()
  }
}
