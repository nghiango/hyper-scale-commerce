package com.hyperscale.commerce.concurrency

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KotlinCoroutinesEvaluationTest {

  @Test
  fun `evaluate structured concurrency fanout and cooperative cancellation in test harness`() =
      runBlocking {
        val completedCount = AtomicInteger(0)
        val fanout = 100

        val results = coroutineScope {
          (1..fanout)
              .map { id ->
                async {
                  delay(5)
                  completedCount.incrementAndGet()
                  "result-$id"
                }
              }
              .awaitAll()
        }

        assertThat(results).hasSize(fanout)
        assertThat(completedCount.get()).isEqualTo(fanout)
      }

  @Test
  fun `failing child cancels its sibling within the parent scope`() = runTest {
    val siblingCancelled = CompletableDeferred<Unit>()

    val failure =
        runCatching {
              coroutineScope {
                launch {
                  try {
                    awaitCancellation()
                  } finally {
                    siblingCancelled.complete(Unit)
                  }
                }
                launch {
                  yield()
                  error("child failure")
                }
              }
            }
            .exceptionOrNull()

    assertThat(failure).isInstanceOf(IllegalStateException::class.java)
    siblingCancelled.await()
    assertThat(siblingCancelled.isCompleted).isTrue()
  }
}
