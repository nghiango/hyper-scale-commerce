@file:Suppress("TooGenericExceptionCaught")

package com.hyperscale.commerce.config.datasource

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.support.TransactionSynchronizationManager

enum class DataSourceType {
  PRIMARY,
  REPLICA,
}

class ReplicationLagTracker(
    val maxAllowableLagMs: Long = 100L,
) {
  private val currentLagMs = AtomicLong(0L)
  private val replicaHealthy = AtomicBoolean(false)

  fun recordLag(lagMs: Long) {
    currentLagMs.set(lagMs)
    replicaHealthy.set(true)
  }

  fun setReplicaHealthy(healthy: Boolean) {
    replicaHealthy.set(healthy)
  }

  fun isLagAcceptable(): Boolean {
    return replicaHealthy.get() && currentLagMs.get() <= maxAllowableLagMs
  }

  fun getCurrentLagMs(): Long = currentLagMs.get()
}

class ReplicaLagMonitor(
    private val replicaJdbc: JdbcOperations,
    private val lagTracker: ReplicationLagTracker,
) {
  @Scheduled(fixedDelayString = "\${app.datasource.replica.lag-check-interval-ms:1000}")
  fun sample() {
    try {
      val lag = replicaJdbc.queryForObject(REPLICA_LAG_SQL, Double::class.java)
      if (lag == null) {
        lagTracker.setReplicaHealthy(false)
      } else {
        lagTracker.recordLag(lag.toLong().coerceAtLeast(0L))
      }
    } catch (_: Exception) {
      lagTracker.setReplicaHealthy(false)
    }
  }

  private companion object {
    val REPLICA_LAG_SQL =
        """
        SELECT CASE
          WHEN NOT pg_is_in_recovery() THEN NULL
          WHEN pg_last_wal_receive_lsn() = pg_last_wal_replay_lsn() THEN 0
          ELSE EXTRACT(EPOCH FROM (clock_timestamp() - pg_last_xact_replay_timestamp())) * 1000
        END
        """
            .trimIndent()
  }
}

class TransactionRoutingDataSource(
    val lagTracker: ReplicationLagTracker = ReplicationLagTracker(),
) : AbstractRoutingDataSource() {
  private val log = LoggerFactory.getLogger(TransactionRoutingDataSource::class.java)

  public override fun determineCurrentLookupKey(): Any {
    val isReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly()

    if (isReadOnly) {
      if (lagTracker.isLagAcceptable()) {
        return DataSourceType.REPLICA
      }
      log.debug(
          "Replica lag ({}ms) exceeds threshold, falling back to PRIMARY",
          lagTracker.getCurrentLagMs())
    }

    return DataSourceType.PRIMARY
  }
}
