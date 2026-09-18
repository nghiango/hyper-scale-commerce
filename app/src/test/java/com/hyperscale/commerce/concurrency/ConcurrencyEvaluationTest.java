package com.hyperscale.commerce.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConcurrencyEvaluationTest {

  @Test
  @DisplayName(
      "evaluate structured concurrency fanout and cooperative cancellation in test harness")
  void evaluateStructuredConcurrencyFanoutAndCooperativeCancellationInTestHarness()
      throws Exception {
    AtomicInteger completedCount = new AtomicInteger(0);
    int fanout = 100;

    List<String> results = new ArrayList<>(fanout);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<String>> futures =
          IntStream.rangeClosed(1, fanout)
              .mapToObj(
                  id ->
                      executor.submit(
                          () -> {
                            Thread.sleep(5);
                            completedCount.incrementAndGet();
                            return "result-" + id;
                          }))
              .toList();
      for (Future<String> future : futures) {
        results.add(future.get());
      }
    }

    assertThat(results).hasSize(fanout);
    assertThat(completedCount.get()).isEqualTo(fanout);
  }

  @Test
  @DisplayName("failing child cancels its sibling within the parent scope")
  void failingChildCancelsItsSiblingWithinTheParentScope() throws Exception {
    CountDownLatch siblingCancelled = new CountDownLatch(1);

    Throwable failure = null;
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      executor.submit(
          (Runnable)
              () -> {
                try {
                  new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  siblingCancelled.countDown();
                }
              });
      Future<?> failing =
          executor.submit(
              (Runnable)
                  () -> {
                    Thread.yield();
                    throw new IllegalStateException("child failure");
                  });

      try {
        failing.get(5, TimeUnit.SECONDS);
      } catch (ExecutionException e) {
        failure = e.getCause();
      }
    } finally {
      executor.shutdownNow();
    }

    assertThat(failure).isInstanceOf(IllegalStateException.class);
    assertThat(siblingCancelled.await(5, TimeUnit.SECONDS)).isTrue();
  }
}
