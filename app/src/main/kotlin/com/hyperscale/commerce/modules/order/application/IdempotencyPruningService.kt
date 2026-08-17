package com.hyperscale.commerce.modules.order.application

import com.hyperscale.commerce.config.storage.StoragePruningProperties
import com.hyperscale.commerce.modules.order.domain.IdempotencyRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class IdempotencyPruningService(
    private val idempotencyRepository: IdempotencyRepository,
    private val properties: StoragePruningProperties,
    @Autowired(required = false) meterRegistry: MeterRegistry? = null,
) {

  private val log = LoggerFactory.getLogger(javaClass)

  private val idempotencyPrunedCounter: Counter? =
      meterRegistry?.counter("storage_pruned_rows_total", "table", "idempotency_keys")

  @Scheduled(
      fixedDelayString = "\${app.storage.pruning.interval-ms:3600000}",
      initialDelayString = "\${app.storage.pruning.initial-delay-ms:60000}",
  )
  fun pruneScheduled() {
    if (!properties.enabled) return
    pruneIdempotency()
  }

  fun pruneIdempotency(): Int {
    val threshold = Instant.now().minus(Duration.ofHours(properties.idempotencyRetentionHours))
    var totalPruned = 0
    while (true) {
      val pruned = idempotencyRepository.pruneExpired(threshold, properties.batchSize)
      totalPruned += pruned
      idempotencyPrunedCounter?.increment(pruned.toDouble())
      if (pruned < properties.batchSize) break
    }
    if (totalPruned > 0) {
      log.info("Pruned $totalPruned expired idempotency keys older than $threshold")
    }
    return totalPruned
  }
}
