package com.hyperscale.commerce.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class ContextSafetyConcurrencyTest {

  private final brave.Tracing braveTracing =
      brave.Tracing.newBuilder().localServiceName("concurrency-test").build();
  private final Tracer tracer =
      new BraveTracer(
          braveTracing.tracer(), new BraveCurrentTraceContext(braveTracing.currentTraceContext()));
  private final ExecutorService executor = Executors.newFixedThreadPool(16);

  @BeforeEach
  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  @DisplayName(
      "concurrent tasks propagate and isolate MDC and trace contexts without cross contamination")
  void concurrentTasksPropagateAndIsolateMdcAndTraceContextsWithoutCrossContamination()
      throws Exception {
    int concurrency = 64;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(concurrency);
    ConcurrentHashMap<String, List<String>> observedContexts = new ConcurrentHashMap<>();
    AtomicInteger leakageCount = new AtomicInteger(0);

    List<Callable<Void>> tasks = new ArrayList<>();
    for (int index = 1; index <= concurrency; index++) {
      final int taskIndex = index;
      tasks.add(
          (Callable<Void>)
              () -> {
                startLatch.await();
                String correlationId = "corr-" + taskIndex + "-" + UUID.randomUUID();
                var span = tracer.nextSpan().name("test-task-" + taskIndex).start();
                var scope = tracer.withSpan(span);

                MDC.put("correlationId", correlationId);
                MDC.put("traceId", span.context().traceId());

                try {
                  // Simulate work with thread yield
                  Thread.sleep(taskIndex % 5L);

                  String currentCorr = MDC.get("correlationId");
                  String currentTrace = MDC.get("traceId");
                  String currentSpan =
                      tracer.currentSpan() != null
                          ? tracer.currentSpan().context().traceId()
                          : null;

                  if (!correlationId.equals(currentCorr)
                      || !span.context().traceId().equals(currentTrace)) {
                    leakageCount.incrementAndGet();
                  }

                  observedContexts
                      .computeIfAbsent(Thread.currentThread().getName(), k -> new ArrayList<>())
                      .add(currentCorr);
                } finally {
                  MDC.remove("correlationId");
                  MDC.remove("traceId");
                  scope.close();
                  span.end();
                  doneLatch.countDown();
                }
                return null;
              });
    }

    for (Callable<Void> task : tasks) {
      executor.submit(task);
    }
    startLatch.countDown();
    boolean completed = doneLatch.await(10, TimeUnit.SECONDS);

    assertThat(completed).isTrue();
    assertThat(leakageCount.get()).isEqualTo(0);
    assertThat(observedContexts.values().stream().flatMap(List::stream).toList())
        .hasSize(concurrency);
  }

  @Test
  @DisplayName("thread local context is completely cleared when task finishes")
  void threadLocalContextIsCompletelyClearedWhenTaskFinishes() throws Exception {
    var singleExecutor = Executors.newSingleThreadExecutor();
    try {
      Future<String> future1 =
          singleExecutor.submit(
              (Callable<String>)
                  () -> {
                    MDC.put("correlationId", "initial-request");
                    var span = tracer.nextSpan().name("initial").start();
                    var scope = tracer.withSpan(span);
                    try {
                      return MDC.get("correlationId");
                    } finally {
                      MDC.remove("correlationId");
                      scope.close();
                      span.end();
                    }
                  });
      future1.get(2, TimeUnit.SECONDS);

      Future<Object[]> future2 =
          singleExecutor.submit(
              (Callable<Object[]>)
                  () -> {
                    String residualMdc = MDC.get("correlationId");
                    var residualSpan = tracer.currentSpan();
                    return new Object[] {residualMdc, residualSpan};
                  });
      Object[] result = future2.get(2, TimeUnit.SECONDS);
      Object residualMdc = result[0];
      Object residualSpan = result[1];

      assertThat(residualMdc).isNull();
      assertThat(residualSpan).isNull();
    } finally {
      singleExecutor.shutdownNow();
    }
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
    braveTracing.close();
  }
}
