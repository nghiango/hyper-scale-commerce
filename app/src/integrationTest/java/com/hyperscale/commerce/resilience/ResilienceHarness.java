package com.hyperscale.commerce.resilience;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
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

  public static void awaitReadiness(int port) throws InterruptedException {
    awaitReadiness(port, DEFAULT_TIMEOUT_SECONDS);
  }

  public static void awaitReadiness(int port, long timeoutSeconds) throws InterruptedException {
    awaitHealthContains(port, "\"UP\"", timeoutSeconds, "/actuator/health/readiness");
  }

  public static void awaitHealthContains(int port, String expected) throws InterruptedException {
    awaitHealthContains(port, expected, DEFAULT_TIMEOUT_SECONDS, "/actuator/health");
  }

  public static void awaitHealthContains(int port, String expected, String path)
      throws InterruptedException {
    awaitHealthContains(port, expected, DEFAULT_TIMEOUT_SECONDS, path);
  }

  public static void awaitHealthContains(
      int port, String expected, long timeoutSeconds, String path) throws InterruptedException {
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
      Thread.sleep(POLL_MILLIS);
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

  public static void writeEvidence(String section) throws IOException {
    File reportFile = new File("../docs/bootcamp/evidence/p6-resilience.md");
    reportFile.getParentFile().mkdirs();
    String existing = reportFile.exists() ? Files.readString(reportFile.toPath()) : "";
    String heading =
        section.lines().filter(line -> line.startsWith("## ")).findFirst().orElseThrow();
    String updated;
    if (existing.contains(heading)) {
      String before = existing.substring(0, existing.indexOf(heading)).strip();
      String after = existing.substring(existing.indexOf(heading) + heading.length());
      int nextHeading = after.indexOf("\n## ");
      String remainder = nextHeading >= 0 ? after.substring(nextHeading + 1) : "";
      String body = section.stripTrailing() + "\n" + remainder;
      updated = before.isEmpty() ? body : before + "\n\n" + body;
    } else {
      updated = existing.stripTrailing() + "\n\n" + section.stripTrailing() + "\n";
    }
    Files.writeString(reportFile.toPath(), updated);
  }

  private static HttpResponse<String> get(int port, String path)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
