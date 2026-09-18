package com.hyperscale.commerce.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class VirtualThreadsQualificationTest {

  private final brave.Tracing braveTracing =
      brave.Tracing.newBuilder().localServiceName("vthread-qualification").build();
  private final Tracer tracer =
      new BraveTracer(
          braveTracing.tracer(), new BraveCurrentTraceContext(braveTracing.currentTraceContext()));

  @BeforeEach
  @AfterEach
  void cleanMdc() {
    MDC.clear();
  }

  @Test
  @DisplayName("virtual thread tasks isolate explicitly established trace and MDC context")
  void virtualThreadTasksIsolateExplicitlyEstablishedTraceAndMdcContext() throws Exception {
    int concurrency = 200;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(concurrency);
    ConcurrentHashMap<String, String> observedContexts = new ConcurrentHashMap<>();
    AtomicInteger leakageCount = new AtomicInteger(0);

    var vthreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      for (int index = 1; index <= concurrency; index++) {
        final int taskIndex = index;
        vthreadExecutor.submit(
            (Callable<Void>)
                () -> {
                  startLatch.await();
                  String correlationId = "vthread-" + taskIndex + "-" + UUID.randomUUID();
                  var span = tracer.nextSpan().name("vthread-task-" + taskIndex).start();
                  var scope = tracer.withSpan(span);

                  MDC.put("correlationId", correlationId);
                  MDC.put("traceId", span.context().traceId());

                  try {
                    Thread.sleep(taskIndex % 10L);
                    String currentCorr = MDC.get("correlationId");
                    String currentTrace = MDC.get("traceId");

                    if (!correlationId.equals(currentCorr)
                        || !span.context().traceId().equals(currentTrace)) {
                      leakageCount.incrementAndGet();
                    }

                    observedContexts.put(correlationId, Thread.currentThread().toString());
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

      startLatch.countDown();
      boolean completed = doneLatch.await(10, TimeUnit.SECONDS);

      assertThat(completed).isTrue();
      assertThat(leakageCount.get()).isEqualTo(0);
      assertThat(observedContexts).hasSize(concurrency);
    } finally {
      vthreadExecutor.shutdownNow();
    }
  }

  @Test
  @DisplayName("virtual thread boundary does not inherit an active Spring transaction")
  void virtualThreadBoundaryDoesNotInheritAnActiveSpringTransaction() throws Exception {
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      Future<Boolean> childSeesTransaction =
          executor.submit(() -> TransactionSynchronizationManager.isActualTransactionActive());

      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      assertThat(childSeesTransaction.get(2, TimeUnit.SECONDS)).isFalse();
    } finally {
      TransactionSynchronizationManager.clear();
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("MDC is not inherited while Brave scope is inherited across virtual thread boundary")
  void mdcIsNotInheritedWhileBraveScopeIsInheritedAcrossVirtualThreadBoundary() throws Exception {
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    var parentSpan = tracer.nextSpan().name("parent").start();
    var parentScope = tracer.withSpan(parentSpan);
    MDC.put("correlationId", "parent-correlation");
    try {
      Future<String[]> inherited =
          executor.submit(
              () ->
                  new String[] {
                    MDC.get("correlationId"),
                    tracer.currentSpan() != null ? tracer.currentSpan().context().traceId() : null
                  });

      String[] result = inherited.get(2, TimeUnit.SECONDS);
      String correlationId = result[0];
      String traceId = result[1];
      assertThat(correlationId).isNull();
      assertThat(traceId).isEqualTo(parentSpan.context().traceId());
    } finally {
      MDC.remove("correlationId");
      parentScope.close();
      parentSpan.end();
      executor.shutdownNow();
    }
  }

  @AfterEach
  void tearDown() {
    braveTracing.close();
  }
}
