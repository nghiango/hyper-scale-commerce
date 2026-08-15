package com.hyperscale.commerce.config.backoff

import java.util.concurrent.ThreadLocalRandom
import org.springframework.util.backoff.BackOff
import org.springframework.util.backoff.BackOffExecution

class ExponentialBackOffWithJitter(
    private val maxRetries: Long = 3L,
    var initialInterval: Long = 200L,
    var multiplier: Double = 2.0,
    var maxInterval: Long = 2000L,
    var jitter: Double = 0.5,
) : BackOff {

  override fun start(): BackOffExecution {
    return object : BackOffExecution {
      private var currentInterval = initialInterval.toDouble()
      private var currentAttempts = 0L

      override fun nextBackOff(): Long {
        if (currentAttempts >= maxRetries) {
          return BackOffExecution.STOP
        }
        currentAttempts++
        val base = currentInterval
        currentInterval = (currentInterval * multiplier).coerceAtMost(maxInterval.toDouble())

        // Apply jitter: [base * (1 - jitter) .. base * (1 + jitter)]
        val minJitter = (base * (1.0 - jitter)).coerceAtLeast(0.0)
        val maxJitter = base * (1.0 + jitter)
        return ThreadLocalRandom.current().nextDouble(minJitter, maxJitter).toLong()
      }
    }
  }
}
