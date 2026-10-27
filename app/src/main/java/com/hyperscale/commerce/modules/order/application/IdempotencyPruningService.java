package com.hyperscale.commerce.modules.order.application;

import com.hyperscale.commerce.config.storage.StoragePruningProperties;
import com.hyperscale.commerce.modules.order.domain.IdempotencyRepository;
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
public class IdempotencyPruningService {
  private final IdempotencyRepository idempotencyRepository;
  private final StoragePruningProperties properties;
  private final Logger log = LoggerFactory.getLogger(getClass());
  private final Counter idempotencyPrunedCounter;

  public IdempotencyPruningService(
      IdempotencyRepository idempotencyRepository,
      StoragePruningProperties properties,
      @Autowired(required = false) @Nullable MeterRegistry meterRegistry) {
    this.idempotencyRepository = idempotencyRepository;
    this.properties = properties;
    this.idempotencyPrunedCounter =
        meterRegistry != null
            ? meterRegistry.counter("storage_pruned_rows_total", "table", "idempotency_keys")
            : null;
  }

  @Scheduled(
      fixedDelayString = "${app.storage.pruning.interval-ms:3600000}",
      initialDelayString = "${app.storage.pruning.initial-delay-ms:60000}")
  public void pruneScheduled() {
    if (!properties.enabled()) return;
    pruneIdempotency();
  }

  public int pruneIdempotency() {
    Instant threshold =
        Instant.now().minus(Duration.ofHours(properties.idempotencyRetentionHours()));
    int totalPruned = 0;
    while (true) {
      int pruned = idempotencyRepository.pruneExpired(threshold, properties.batchSize());
      totalPruned += pruned;
      if (idempotencyPrunedCounter != null) {
        idempotencyPrunedCounter.increment(pruned);
      }
      if (pruned < properties.batchSize()) break;
    }
    if (totalPruned > 0) {
      log.info("Pruned {} expired idempotency keys older than {}", totalPruned, threshold);
    }
    return totalPruned;
  }
}
