package com.hyperscale.commerce.modules.shared.outbox

import com.hyperscale.commerce.config.storage.StoragePruningProperties
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class OutboxPruningService(
    private val outboxRepository: OutboxRepository,
    private val properties: StoragePruningProperties,
    @Autowired(required = false) meterRegistry: MeterRegistry? = null,
) {

  private val log = LoggerFactory.getLogger(javaClass)

  private val outboxPrunedCounter: Counter? =
      meterRegistry?.counter("storage_pruned_rows_total", "table", "outbox_events")

  @Scheduled(
      fixedDelayString = "\${app.storage.pruning.interval-ms:3600000}",
      initialDelayString = "\${app.storage.pruning.initial-delay-ms:60000}",
  )
  fun pruneScheduled() {
    if (!properties.enabled) return
    pruneOutbox()
  }

  fun pruneOutbox(): Int {
    val threshold = Instant.now().minus(Duration.ofDays(properties.outboxRetentionDays))
    var totalPruned = 0
    while (true) {
      val pruned = outboxRepository.prunePublished(threshold, properties.batchSize)
      totalPruned += pruned
      outboxPrunedCounter?.increment(pruned.toDouble())
      if (pruned < properties.batchSize) break
    }
    if (totalPruned > 0) {
      log.info("Pruned $totalPruned published outbox events older than $threshold")
    }
    return totalPruned
  }
}
