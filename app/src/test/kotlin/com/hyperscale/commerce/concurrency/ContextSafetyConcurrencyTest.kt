package com.hyperscale.commerce.concurrency

import io.micrometer.tracing.Tracer
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext
import io.micrometer.tracing.brave.bridge.BraveTracer
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class ContextSafetyConcurrencyTest {

  private val braveTracing = brave.Tracing.newBuilder().localServiceName("concurrency-test").build()
  private val tracer: Tracer =
      BraveTracer(
          braveTracing.tracer(), BraveCurrentTraceContext(braveTracing.currentTraceContext()))
  private val executor = Executors.newFixedThreadPool(16)

  @BeforeEach
  @AfterEach
  fun clearMdc() {
    MDC.clear()
  }

  @Test
  fun `concurrent tasks propagate and isolate MDC and trace contexts without cross contamination`() {
    val concurrency = 64
    val startLatch = CountDownLatch(1)
    val doneLatch = CountDownLatch(concurrency)
    val observedContexts = ConcurrentHashMap<String, MutableList<String>>()
    val leakageCount = AtomicInteger(0)

    val tasks =
        (1..concurrency).map { index ->
          Callable {
            startLatch.await()
            val correlationId = "corr-$index-${UUID.randomUUID()}"
            val span = tracer.nextSpan().name("test-task-$index").start()
            val scope = tracer.withSpan(span)

            MDC.put("correlationId", correlationId)
            MDC.put("traceId", span.context().traceId())

            try {
              // Simulate work with thread yield
              Thread.sleep(index % 5L)

              val currentCorr = MDC.get("correlationId")
              val currentTrace = MDC.get("traceId")
              val currentSpan = tracer.currentSpan()?.context()?.traceId()

              if (currentCorr != correlationId || currentTrace != span.context().traceId()) {
                leakageCount.incrementAndGet()
              }

              observedContexts
                  .computeIfAbsent(Thread.currentThread().name) { mutableListOf() }
                  .add(currentCorr)
            } finally {
              MDC.remove("correlationId")
              MDC.remove("traceId")
              scope.close()
              span.end()
              doneLatch.countDown()
            }
          }
        }

    tasks.forEach { executor.submit(it) }
    startLatch.countDown()
    val completed = doneLatch.await(10, TimeUnit.SECONDS)

    assertThat(completed).isTrue()
    assertThat(leakageCount.get()).isEqualTo(0)
    assertThat(observedContexts.values.flatten()).hasSize(concurrency)
  }

  @Test
  fun `thread local context is completely cleared when task finishes`() {
    val singleExecutor = Executors.newSingleThreadExecutor()
    try {
      val future1 =
          singleExecutor.submit(
              Callable {
                MDC.put("correlationId", "initial-request")
                val span = tracer.nextSpan().name("initial").start()
                val scope = tracer.withSpan(span)
                try {
                  MDC.get("correlationId")
                } finally {
                  MDC.remove("correlationId")
                  scope.close()
                  span.end()
                }
              })
      future1.get(2, TimeUnit.SECONDS)

      val future2 =
          singleExecutor.submit(
              Callable {
                val residualMdc = MDC.get("correlationId")
                val residualSpan = tracer.currentSpan()
                residualMdc to residualSpan
              })
      val (residualMdc, residualSpan) = future2.get(2, TimeUnit.SECONDS)

      assertThat(residualMdc).isNull()
      assertThat(residualSpan).isNull()
    } finally {
      singleExecutor.shutdownNow()
    }
  }

  @AfterEach
  fun tearDown() {
    executor.shutdownNow()
    braveTracing.close()
  }
}
