package com.hyperscale.commerce.modules.order.domain;

import java.time.Instant;

public interface IdempotencyRepository {
  boolean tryInsertInProgress(String key, String requestHash);

  IdempotencyRecord findByKey(String key);

  void markCompleted(String key, int responseCode, String responseBody);

  void markFailed(String key);

  int pruneExpired(Instant olderThan, int batchSize);
}
