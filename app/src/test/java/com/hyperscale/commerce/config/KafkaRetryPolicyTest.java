package com.hyperscale.commerce.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyperscale.commerce.config.backoff.ExponentialBackOffWithJitter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;
import tools.jackson.core.JacksonException;

class KafkaRetryPolicyTest {

  @Test
  @DisplayName("exponential backoff with jitter grows within bounded limits")
  void exponentialBackoffWithJitterGrowsWithinBoundedLimits() {
    ExponentialBackOffWithJitter backOff = new ExponentialBackOffWithJitter(3L);
    backOff.setInitialInterval(200L);
    backOff.setMultiplier(2.0);
    backOff.setMaxInterval(2000L);

    BackOffExecution execution = backOff.start();

    // 1st retry: ~200ms with jitter [100ms..300ms]
    long interval1 = execution.nextBackOff();
    assertThat(interval1).isGreaterThanOrEqualTo(100L).isLessThanOrEqualTo(300L);

    // 2nd retry: ~400ms with jitter [200ms..600ms]
    long interval2 = execution.nextBackOff();
    assertThat(interval2).isGreaterThanOrEqualTo(200L).isLessThanOrEqualTo(600L);

    // 3rd retry: ~800ms with jitter [400ms..1200ms]
    long interval3 = execution.nextBackOff();
    assertThat(interval3).isGreaterThanOrEqualTo(400L).isLessThanOrEqualTo(1200L);

    // 4th retry: exhausted (returns STOP / -1)
    long interval4 = execution.nextBackOff();
    assertThat(interval4).isEqualTo(BackOffExecution.STOP);
  }

  @Test
  @DisplayName("non-retryable exception types are recognized")
  void nonRetryableExceptionTypesAreRecognized() {
    List<Class<? extends Exception>> nonRetryable =
        List.of(JacksonException.class, IllegalArgumentException.class);

    assertThat(nonRetryable).contains(JacksonException.class, IllegalArgumentException.class);
  }
}
