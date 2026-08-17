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
import org.springframework.transaction.support.TransactionSynchronizationManager

class VirtualThreadsQualificationTest {

  private val braveTracing =
      brave.Tracing.newBuilder().localServiceName("vthread-qualification").build()
  private val tracer: Tracer =
      BraveTracer(
          braveTracing.tracer(), BraveCurrentTraceContext(braveTracing.currentTraceContext()))

  @BeforeEach
  @AfterEach
  fun cleanMdc() {
    MDC.clear()
  }

  @Test
  fun `virtual thread tasks isolate explicitly established trace and MDC context`() {
    val concurrency = 200
    val startLatch = CountDownLatch(1)
    val doneLatch = CountDownLatch(concurrency)
    val observedContexts = ConcurrentHashMap<String, String>()
    val leakageCount = AtomicInteger(0)

    val vthreadExecutor = Executors.newVirtualThreadPerTaskExecutor()
    try {
      (1..concurrency).forEach { index ->
        vthreadExecutor.submit(
            Callable {
              startLatch.await()
              val correlationId = "vthread-$index-${UUID.randomUUID()}"
              val span = tracer.nextSpan().name("vthread-task-$index").start()
              val scope = tracer.withSpan(span)

              MDC.put("correlationId", correlationId)
              MDC.put("traceId", span.context().traceId())

              try {
                Thread.sleep(index % 10L)
                val currentCorr = MDC.get("correlationId")
                val currentTrace = MDC.get("traceId")

                if (currentCorr != correlationId || currentTrace != span.context().traceId()) {
                  leakageCount.incrementAndGet()
                }

                observedContexts[correlationId] = Thread.currentThread().toString()
              } finally {
                MDC.remove("correlationId")
                MDC.remove("traceId")
                scope.close()
                span.end()
                doneLatch.countDown()
              }
            })
      }

      startLatch.countDown()
      val completed = doneLatch.await(10, TimeUnit.SECONDS)

      assertThat(completed).isTrue()
      assertThat(leakageCount.get()).isEqualTo(0)
      assertThat(observedContexts).hasSize(concurrency)
    } finally {
      vthreadExecutor.shutdownNow()
    }
  }

  @Test
  fun `virtual thread boundary does not inherit an active Spring transaction`() {
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    TransactionSynchronizationManager.setActualTransactionActive(true)
    try {
      val childSeesTransaction =
          executor.submit<Boolean> { TransactionSynchronizationManager.isActualTransactionActive() }

      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
      assertThat(childSeesTransaction.get(2, TimeUnit.SECONDS)).isFalse()
    } finally {
      TransactionSynchronizationManager.clear()
      executor.shutdownNow()
    }
  }

  @Test
  fun `MDC is not inherited while Brave scope is inherited across virtual thread boundary`() {
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    val parentSpan = tracer.nextSpan().name("parent").start()
    val parentScope = tracer.withSpan(parentSpan)
    MDC.put("correlationId", "parent-correlation")
    try {
      val inherited =
          executor.submit<Pair<String?, String?>> {
            MDC.get("correlationId") to tracer.currentSpan()?.context()?.traceId()
          }

      val (correlationId, traceId) = inherited.get(2, TimeUnit.SECONDS)
      assertThat(correlationId).isNull()
      assertThat(traceId).isEqualTo(parentSpan.context().traceId())
    } finally {
      MDC.remove("correlationId")
      parentScope.close()
      parentSpan.end()
      executor.shutdownNow()
    }
  }

  @AfterEach
  fun tearDown() {
    braveTracing.close()
  }
}
