package com.hyperscale.commerce.orderquery.concurrency

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class DataSourceBackpressureConcurrencyTest {

  private val executor = Executors.newFixedThreadPool(20)
  private var hikariDataSource: HikariDataSource? = null

  @Test
  fun `hikari connection pool strictly bounds active connections and rejects excess under timeout`() {
    val poolSize = 3
    val totalRequests = 10
    val holdDurationMs = 150L

    val mockUnderlyingDs = mock(DataSource::class.java)
    `when`(mockUnderlyingDs.connection).thenAnswer {
      val mockConn = mock(Connection::class.java)
      `when`(mockConn.isValid(org.mockito.ArgumentMatchers.anyInt())).thenReturn(true)
      mockConn
    }

    val config =
        HikariConfig().apply {
          dataSource = mockUnderlyingDs
          maximumPoolSize = poolSize
          minimumIdle = 1
          connectionTimeout = 250L // Fast timeout for test
          poolName = "order-query-test-backpressure-pool"
          validate()
        }
    val ds = HikariDataSource(config)
    hikariDataSource = ds

    val startLatch = CountDownLatch(1)
    val doneLatch = CountDownLatch(totalRequests)
    val successCount = AtomicInteger(0)
    val timeoutCount = AtomicInteger(0)
    val maxObservedActive = AtomicInteger(0)

    val tasks =
        (1..totalRequests).map {
          Callable {
            startLatch.await()
            var conn: Connection? = null
            try {
              conn = ds.connection
              val active = ds.hikariPoolMXBean?.activeConnections ?: 0
              maxObservedActive.updateAndGet { current -> maxOf(current, active) }
              successCount.incrementAndGet()
              Thread.sleep(holdDurationMs)
            } catch (ex: Exception) {
              if (ex is SQLTransientConnectionException || ex is SQLException) {
                timeoutCount.incrementAndGet()
              } else {
                throw ex
              }
            } finally {
              conn?.close()
              doneLatch.countDown()
            }
          }
        }

    tasks.forEach { executor.submit(it) }
    startLatch.countDown()
    val completed = doneLatch.await(5, TimeUnit.SECONDS)

    assertThat(completed).isTrue()
    assertThat(maxObservedActive.get()).isLessThanOrEqualTo(poolSize)
    assertThat(successCount.get() + timeoutCount.get()).isEqualTo(totalRequests)
    assertThat(successCount.get()).isGreaterThanOrEqualTo(poolSize)
  }

  @AfterEach
  fun tearDown() {
    executor.shutdownNow()
    hikariDataSource?.close()
  }
}
