package com.hyperscale.commerce.orderquery.config.backoff;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

public class ExponentialBackOffWithJitter implements BackOff {
  private final long maxRetries;
  private long initialInterval;
  private double multiplier;
  private long maxInterval;
  private double jitter;

  public ExponentialBackOffWithJitter(
      long maxRetries, long initialInterval, double multiplier, long maxInterval, double jitter) {
    this.maxRetries = maxRetries;
    this.initialInterval = initialInterval;
    this.multiplier = multiplier;
    this.maxInterval = maxInterval;
    this.jitter = jitter;
  }

  public ExponentialBackOffWithJitter(long maxRetries) {
    this(maxRetries, 200L, 2.0, 2000L, 0.5);
  }

  public ExponentialBackOffWithJitter() {
    this(3L, 200L, 2.0, 2000L, 0.5);
  }

  public long getInitialInterval() {
    return initialInterval;
  }

  public void setInitialInterval(long initialInterval) {
    this.initialInterval = initialInterval;
  }

  public double getMultiplier() {
    return multiplier;
  }

  public void setMultiplier(double multiplier) {
    this.multiplier = multiplier;
  }

  public long getMaxInterval() {
    return maxInterval;
  }

  public void setMaxInterval(long maxInterval) {
    this.maxInterval = maxInterval;
  }

  public double getJitter() {
    return jitter;
  }

  public void setJitter(double jitter) {
    this.jitter = jitter;
  }

  @Override
  public BackOffExecution start() {
    return new BackOffExecution() {
      private double currentInterval = initialInterval;
      private long currentAttempts = 0L;

      @Override
      public long nextBackOff() {
        if (currentAttempts >= maxRetries) {
          return BackOffExecution.STOP;
        }
        currentAttempts++;
        double base = currentInterval;
        currentInterval = Math.min(currentInterval * multiplier, maxInterval);

        // Apply jitter: [base * (1 - jitter) .. base * (1 + jitter)]
        double minJitter = Math.max(base * (1.0 - jitter), 0.0);
        double maxJitter = base * (1.0 + jitter);
        return (long) ThreadLocalRandom.current().nextDouble(minJitter, maxJitter);
      }
    };
  }
}
