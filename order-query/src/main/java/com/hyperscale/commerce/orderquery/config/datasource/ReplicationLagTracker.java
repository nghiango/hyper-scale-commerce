package com.hyperscale.commerce.orderquery.config.datasource;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ReplicationLagTracker {
  private final long maxAllowableLagMs;
  private final AtomicLong currentLagMs = new AtomicLong(0L);
  private final AtomicBoolean replicaHealthy = new AtomicBoolean(false);

  public ReplicationLagTracker(long maxAllowableLagMs) {
    this.maxAllowableLagMs = maxAllowableLagMs;
  }

  public ReplicationLagTracker() {
    this(100L);
  }

  public long getMaxAllowableLagMs() {
    return maxAllowableLagMs;
  }

  public void recordLag(long lagMs) {
    currentLagMs.set(lagMs);
    replicaHealthy.set(true);
  }

  public void setReplicaHealthy(boolean healthy) {
    replicaHealthy.set(healthy);
  }

  public boolean isLagAcceptable() {
    return replicaHealthy.get() && currentLagMs.get() <= maxAllowableLagMs;
  }

  public long getCurrentLagMs() {
    return currentLagMs.get();
  }
}
