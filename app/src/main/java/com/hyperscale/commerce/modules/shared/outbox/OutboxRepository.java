package com.hyperscale.commerce.modules.shared.outbox;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface OutboxRepository {

  long insert(String aggregateId, String eventType, String payload);

  List<OutboxEvent> claimDue(int limit);

  void markPublished(long id);

  void markPublished(Collection<Long> ids);

  double oldestUnpublishedAgeSeconds();

  int prunePublished(Instant olderThan, int batchSize);
}
