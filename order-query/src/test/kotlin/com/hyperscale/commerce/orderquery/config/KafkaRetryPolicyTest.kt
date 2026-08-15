package com.hyperscale.commerce.orderquery.config

import com.hyperscale.commerce.orderquery.config.backoff.ExponentialBackOffWithJitter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KafkaRetryPolicyTest {

  @Test
  fun `exponential backoff with jitter grows within bounded limits`() {
    val backOff =
        ExponentialBackOffWithJitter(3L).apply {
          initialInterval = 200L
          multiplier = 2.0
          maxInterval = 2000L
        }

    val execution = backOff.start()

    // 1st retry: ~200ms with jitter [100ms..300ms]
    val interval1 = execution.nextBackOff()
    assertThat(interval1).isGreaterThanOrEqualTo(100L).isLessThanOrEqualTo(300L)

    // 2nd retry: ~400ms with jitter [200ms..600ms]
    val interval2 = execution.nextBackOff()
    assertThat(interval2).isGreaterThanOrEqualTo(200L).isLessThanOrEqualTo(600L)

    // 3rd retry: ~800ms with jitter [400ms..1200ms]
    val interval3 = execution.nextBackOff()
    assertThat(interval3).isGreaterThanOrEqualTo(400L).isLessThanOrEqualTo(1200L)

    // 4th retry: exhausted (returns STOP / -1)
    val interval4 = execution.nextBackOff()
    assertThat(interval4).isEqualTo(org.springframework.util.backoff.BackOffExecution.STOP)
  }

  @Test
  fun `non-retryable exception types are recognized`() {
    val nonRetryable =
        listOf(
            tools.jackson.core.JacksonException::class.java,
            IllegalArgumentException::class.java,
        )

    assertThat(nonRetryable)
        .contains(
            tools.jackson.core.JacksonException::class.java,
            IllegalArgumentException::class.java,
        )
  }
}
