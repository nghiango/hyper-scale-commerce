package com.hyperscale.commerce.orderquery.resilience;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;

public final class ResilienceHarness {
  private static final HttpClient httpClient = HttpClient.newHttpClient();
  private static final long DEFAULT_TIMEOUT_SECONDS = 30L;
  private static final long POLL_MILLIS = 500L;

  private ResilienceHarness() {}

  public static void stopKafka(KafkaContainer kafka) {
    kafka.stop();
  }

  public static void startKafka(KafkaContainer kafka) {
    kafka.start();
  }

  public static void stopPostgres(PostgreSQLContainer<?> postgres) {
    postgres.stop();
  }

  public static void startPostgres(PostgreSQLContainer<?> postgres) {
    postgres.waitingFor(
        Wait.forLogMessage(".*database system is ready to accept connections.*", 1));
    postgres.start();
  }

  public static void awaitReadiness(int port) {
    awaitReadiness(port, DEFAULT_TIMEOUT_SECONDS);
  }

  public static void awaitReadiness(int port, long timeoutSeconds) {
    awaitHealthContains(port, "\"UP\"", timeoutSeconds, "/actuator/health/readiness");
  }

  public static void awaitHealthContains(int port, String expected) {
    awaitHealthContains(port, expected, DEFAULT_TIMEOUT_SECONDS, "/actuator/health");
  }

  public static void awaitHealthContains(int port, String expected, long timeoutSeconds) {
    awaitHealthContains(port, expected, timeoutSeconds, "/actuator/health");
  }

  public static void awaitHealthContains(int port, String expected, String path) {
    awaitHealthContains(port, expected, DEFAULT_TIMEOUT_SECONDS, path);
  }

  public static void awaitHealthContains(
      int port, String expected, long timeoutSeconds, String path) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    while (System.nanoTime() < deadline) {
      try {
        HttpResponse<String> response = get(port, path);
        if (response.body().contains(expected)) {
          return;
        }
      } catch (Exception e) {
        // dependency or service not yet accepting connections
      }
      try {
        Thread.sleep(POLL_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while awaiting health on port " + port);
      }
    }
    throw new AssertionError(
        "Health on port "
            + port
            + " did not contain '"
            + expected
            + "' within "
            + timeoutSeconds
            + " seconds");
  }

  private static HttpResponse<String> get(int port, String path)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
