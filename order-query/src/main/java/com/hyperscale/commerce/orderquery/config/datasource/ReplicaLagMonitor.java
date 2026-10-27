package com.hyperscale.commerce.orderquery.config.datasource;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.scheduling.annotation.Scheduled;

public class ReplicaLagMonitor {
  private static final String REPLICA_LAG_SQL =
      """
      SELECT CASE
        WHEN NOT pg_is_in_recovery() THEN NULL
        WHEN pg_last_wal_receive_lsn() = pg_last_wal_replay_lsn() THEN 0
        ELSE EXTRACT(EPOCH FROM (clock_timestamp() - pg_last_xact_replay_timestamp())) * 1000
      END""";

  private final JdbcOperations replicaJdbc;
  private final ReplicationLagTracker lagTracker;

  public ReplicaLagMonitor(JdbcOperations replicaJdbc, ReplicationLagTracker lagTracker) {
    this.replicaJdbc = replicaJdbc;
    this.lagTracker = lagTracker;
  }

  @Scheduled(fixedDelayString = "${app.datasource.replica.lag-check-interval-ms:1000}")
  public void sample() {
    try {
      Double lag = replicaJdbc.queryForObject(REPLICA_LAG_SQL, Double.class);
      if (lag == null) {
        lagTracker.setReplicaHealthy(false);
      } else {
        lagTracker.recordLag(Math.max(lag.longValue(), 0L));
      }
    } catch (Exception ignored) {
      lagTracker.setReplicaHealthy(false);
    }
  }
}
