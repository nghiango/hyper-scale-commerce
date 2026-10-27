package com.hyperscale.commerce.modules.shared.outbox;

import com.hyperscale.commerce.config.storage.StoragePruningProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class OutboxPruningService {
  private final OutboxRepository outboxRepository;
  private final StoragePruningProperties properties;
  private final Logger log = LoggerFactory.getLogger(getClass());
  private final Counter outboxPrunedCounter;

  public OutboxPruningService(
      OutboxRepository outboxRepository,
      StoragePruningProperties properties,
      @Autowired(required = false) @Nullable MeterRegistry meterRegistry) {
    this.outboxRepository = outboxRepository;
    this.properties = properties;
    this.outboxPrunedCounter =
        meterRegistry != null
            ? meterRegistry.counter("storage_pruned_rows_total", "table", "outbox_events")
            : null;
  }

  @Scheduled(
      fixedDelayString = "${app.storage.pruning.interval-ms:3600000}",
      initialDelayString = "${app.storage.pruning.initial-delay-ms:60000}")
  public void pruneScheduled() {
    if (!properties.enabled()) return;
    pruneOutbox();
  }

  public int pruneOutbox() {
    Instant threshold = Instant.now().minus(Duration.ofDays(properties.outboxRetentionDays()));
    int totalPruned = 0;
    while (true) {
      int pruned = outboxRepository.prunePublished(threshold, properties.batchSize());
      totalPruned += pruned;
      if (outboxPrunedCounter != null) {
        outboxPrunedCounter.increment(pruned);
      }
      if (pruned < properties.batchSize()) break;
    }
    if (totalPruned > 0) {
      log.info("Pruned {} published outbox events older than {}", totalPruned, threshold);
    }
    return totalPruned;
  }
}
