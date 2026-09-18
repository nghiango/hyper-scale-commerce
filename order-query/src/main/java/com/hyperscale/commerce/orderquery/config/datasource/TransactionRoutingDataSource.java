package com.hyperscale.commerce.orderquery.config.datasource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class TransactionRoutingDataSource extends AbstractRoutingDataSource {
  private static final Logger log = LoggerFactory.getLogger(TransactionRoutingDataSource.class);

  private final ReplicationLagTracker lagTracker;

  public TransactionRoutingDataSource(ReplicationLagTracker lagTracker) {
    this.lagTracker = lagTracker;
  }

  public TransactionRoutingDataSource() {
    this(new ReplicationLagTracker());
  }

  public ReplicationLagTracker getLagTracker() {
    return lagTracker;
  }

  @Override
  public Object determineCurrentLookupKey() {
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      if (lagTracker.isLagAcceptable()) {
        return DataSourceType.REPLICA;
      }
      log.debug(
          "Replica lag ({}ms) exceeds threshold, falling back to PRIMARY",
          lagTracker.getCurrentLagMs());
    }
    return DataSourceType.PRIMARY;
  }
}
