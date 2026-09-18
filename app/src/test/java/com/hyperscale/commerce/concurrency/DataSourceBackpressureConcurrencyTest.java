package com.hyperscale.commerce.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DataSourceBackpressureConcurrencyTest {

  private final ExecutorService executor = Executors.newFixedThreadPool(20);
  private HikariDataSource hikariDataSource;

  @Test
  @DisplayName(
      "hikari connection pool strictly bounds active connections and rejects excess under timeout")
  void hikariConnectionPoolStrictlyBoundsActiveConnectionsAndRejectsExcessUnderTimeout()
      throws Exception {
    int poolSize = 3;
    int totalRequests = 10;
    long holdDurationMs = 150L;

    DataSource mockUnderlyingDs = mock(DataSource.class);
    when(mockUnderlyingDs.getConnection())
        .thenAnswer(
            invocation -> {
              Connection mockConn = mock(Connection.class);
              when(mockConn.isValid(anyInt())).thenReturn(true);
              return mockConn;
            });

    HikariConfig config = new HikariConfig();
    config.setDataSource(mockUnderlyingDs);
    config.setMaximumPoolSize(poolSize);
    config.setMinimumIdle(1);
    config.setConnectionTimeout(250L); // Fast timeout for test
    config.setPoolName("test-backpressure-pool");
    config.validate();
    HikariDataSource ds = new HikariDataSource(config);
    hikariDataSource = ds;

    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(totalRequests);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger timeoutCount = new AtomicInteger(0);
    AtomicInteger maxObservedActive = new AtomicInteger(0);

    List<Callable<Void>> tasks = new ArrayList<>();
    for (int i = 1; i <= totalRequests; i++) {
      tasks.add(
          (Callable<Void>)
              () -> {
                startLatch.await();
                Connection conn = null;
                try {
                  conn = ds.getConnection();
                  int active =
                      ds.getHikariPoolMXBean() != null
                          ? ds.getHikariPoolMXBean().getActiveConnections()
                          : 0;
                  maxObservedActive.updateAndGet(current -> Math.max(current, active));
                  successCount.incrementAndGet();
                  Thread.sleep(holdDurationMs);
                } catch (Exception ex) {
                  if (ex instanceof SQLTransientConnectionException || ex instanceof SQLException) {
                    timeoutCount.incrementAndGet();
                  } else {
                    throw ex;
                  }
                } finally {
                  if (conn != null) {
                    conn.close();
                  }
                  doneLatch.countDown();
                }
                return null;
              });
    }

    for (Callable<Void> task : tasks) {
      executor.submit(task);
    }
    startLatch.countDown();
    boolean completed = doneLatch.await(5, TimeUnit.SECONDS);

    assertThat(completed).isTrue();
    assertThat(maxObservedActive.get()).isLessThanOrEqualTo(poolSize);
    assertThat(successCount.get() + timeoutCount.get()).isEqualTo(totalRequests);
    assertThat(successCount.get()).isGreaterThanOrEqualTo(poolSize);
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
    if (hikariDataSource != null) {
      hikariDataSource.close();
    }
  }
}
