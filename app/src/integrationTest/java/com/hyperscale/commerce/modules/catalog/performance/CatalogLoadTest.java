package com.hyperscale.commerce.modules.catalog.performance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"app.rate-limiting.enabled=false"})
public class CatalogLoadTest {

  private static final int CONCURRENT_USERS = 10;
  private static final int RAMP_UP_SECONDS = 1;
  private static final int DURATION_SECONDS = 5;
  private static final int MAX_PRODUCT_ID = 1000;
  private static final int LIST_PAGE = 0;
  private static final int LIST_SIZE = 20;
  private static final String SEARCH_QUERY = "Product";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

  private final int port;

  @Autowired
  public CatalogLoadTest(@LocalServerPort int port) {
    this.port = port;
  }

  private String baseUrl() {
    return "http://localhost:" + port + "/catalog/products";
  }

  @Test
  @DisplayName("produces a baseline report for catalog endpoints")
  void producesABaselineReportForCatalogEndpoints() throws Exception {
    HttpClient httpClient = HttpClient.newHttpClient();

    List<LoadScenario> scenarios =
        List.of(
            new LoadScenario(
                "GET /catalog/products?page=" + LIST_PAGE + "&size=" + LIST_SIZE,
                this::listRequest),
            new LoadScenario("GET /catalog/products/{id}", this::byIdRequest),
            new LoadScenario(
                "GET /catalog/products?query="
                    + SEARCH_QUERY
                    + "&page="
                    + LIST_PAGE
                    + "&size="
                    + LIST_SIZE,
                this::searchRequest),
            new LoadScenario("GET /catalog/products/{id}/availability", this::availabilityRequest));

    List<LoadResult> results = new ArrayList<>();
    for (LoadScenario scenario : scenarios) {
      results.add(
          new LoadTest(httpClient, scenario.name(), scenario.requestFactory())
              .run(CONCURRENT_USERS, RAMP_UP_SECONDS, DURATION_SECONDS));
    }

    String report = buildMarkdownReport(results);
    File reportFile = new File("../docs/bootcamp/evidence/p2-baseline.md");
    reportFile.getParentFile().mkdirs();
    Files.writeString(reportFile.toPath(), report);

    results.forEach(
        result -> {
          assertThat(result.throughputRps()).isPositive();
          assertThat(result.errorRate()).isEqualTo(0.0);
        });
  }

  private HttpRequest listRequest() {
    return HttpRequest.newBuilder(
            URI.create(baseUrl() + "?page=" + LIST_PAGE + "&size=" + LIST_SIZE))
        .GET()
        .build();
  }

  private HttpRequest byIdRequest() {
    int id = ThreadLocalRandom.current().nextInt(1, MAX_PRODUCT_ID + 1);
    return HttpRequest.newBuilder(URI.create(baseUrl() + "/" + id)).GET().build();
  }

  private HttpRequest searchRequest() {
    return HttpRequest.newBuilder(
            URI.create(
                baseUrl() + "?query=" + SEARCH_QUERY + "&page=" + LIST_PAGE + "&size=" + LIST_SIZE))
        .GET()
        .build();
  }

  private HttpRequest availabilityRequest() {
    int id = ThreadLocalRandom.current().nextInt(1, MAX_PRODUCT_ID + 1);
    return HttpRequest.newBuilder(URI.create(baseUrl() + "/" + id + "/availability")).GET().build();
  }

  private String buildMarkdownReport(List<LoadResult> results) {
    StringBuilder builder = new StringBuilder();
    builder.append("# Phase 2 — Catalog API Baseline Report").append('\n');
    builder.append('\n');
    builder
        .append("Environment: local Docker PostgreSQL, JDK 21, Spring Boot, 1,000 seeded products.")
        .append('\n');
    builder
        .append(
            "Concurrency: "
                + CONCURRENT_USERS
                + " users, ramp-up: "
                + RAMP_UP_SECONDS
                + "s, duration: "
                + DURATION_SECONDS
                + "s.")
        .append('\n');
    builder.append('\n');
    builder
        .append("| Endpoint | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (RPS) | Error rate |")
        .append('\n');
    builder.append("|---|---:|---:|---:|---:|---:|").append('\n');

    results.forEach(
        result ->
            builder
                .append(
                    "| "
                        + result.name()
                        + " | "
                        + format(result.p50(), 2)
                        + " | "
                        + format(result.p95(), 2)
                        + " | "
                        + format(result.p99(), 2)
                        + " | "
                        + format(result.throughputRps(), 2)
                        + " | "
                        + format(result.errorRate(), 2)
                        + " |")
                .append('\n'));

    builder.append('\n');
    builder.append("Raw totals:").append('\n');
    results.forEach(
        result ->
            builder
                .append(
                    "- "
                        + result.name()
                        + ": "
                        + result.totalRequests()
                        + " total, "
                        + result.successfulRequests()
                        + " successful, "
                        + result.failedRequests()
                        + " failed")
                .append('\n'));

    return builder.toString();
  }

  private static String format(double value, int digits) {
    return String.format("%." + digits + "f", value);
  }

  private record LoadScenario(String name, Supplier<HttpRequest> requestFactory) {}
}
