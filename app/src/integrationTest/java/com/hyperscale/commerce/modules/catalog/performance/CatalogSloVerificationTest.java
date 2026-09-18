package com.hyperscale.commerce.modules.catalog.performance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Files;
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
public class CatalogSloVerificationTest {

  private static final int RAMP_UP_SECONDS = 1;
  private static final int DURATION_SECONDS = 5;
  private static final int MIN_PRODUCT_ID = 1;
  private static final int MAX_PRODUCT_ID_EXCLUSIVE = 1001;
  private static final int LIST_PAGE = 0;
  private static final int LIST_SIZE = 20;
  private static final String SEARCH_QUERY = "Product";
  private static final int BY_ID_CONCURRENCY = 100;
  private static final int LIST_CONCURRENCY = 100;
  private static final int SEARCH_CONCURRENCY = 50;
  private static final int SPIKE_CONCURRENCY = 500;
  private static final double BY_ID_P95_SLO_MS = 100.0;
  private static final double LIST_P95_SLO_MS = 200.0;
  private static final double SEARCH_P95_SLO_MS = 300.0;
  private static final double SPIKE_P95_SLO_MS = 300.0;

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

  private final int port;
  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Autowired
  public CatalogSloVerificationTest(@LocalServerPort int port) {
    this.port = port;
  }

  private String baseUrl() {
    return "http://localhost:" + port + "/catalog/products";
  }

  @Test
  @DisplayName("verifies phase 2 SLOs and writes p2-slo-verification report")
  void verifiesPhase2SlosAndWritesP2SloVerificationReport() throws Exception {
    LoadResult byId =
        runScenario("GET /catalog/products/{id}", BY_ID_CONCURRENCY, this::byIdRequest);
    LoadResult list =
        runScenario(
            "GET /catalog/products?page=" + LIST_PAGE + "&size=" + LIST_SIZE,
            LIST_CONCURRENCY,
            this::listRequest);
    LoadResult search =
        runScenario(
            "GET /catalog/products?query="
                + SEARCH_QUERY
                + "&page="
                + LIST_PAGE
                + "&size="
                + LIST_SIZE,
            SEARCH_CONCURRENCY,
            this::searchRequest);
    LoadResult spike =
        runScenario("GET /catalog/products/{id} (5x spike)", SPIKE_CONCURRENCY, this::byIdRequest);

    List<SloCheck> checks =
        List.of(
            new SloCheck(
                "p95 < 100ms at 100 RPS",
                "GET /catalog/products/{id}",
                byId,
                BY_ID_CONCURRENCY,
                BY_ID_P95_SLO_MS),
            new SloCheck(
                "p95 < 200ms at 100 RPS",
                "GET /catalog/products",
                list,
                LIST_CONCURRENCY,
                LIST_P95_SLO_MS),
            new SloCheck(
                "p95 < 300ms at 50 RPS",
                "GET /catalog/products?query=Product",
                search,
                SEARCH_CONCURRENCY,
                SEARCH_P95_SLO_MS),
            new SloCheck(
                "p95 < 300ms at 500 RPS",
                "GET /catalog/products/{id} (5x spike)",
                spike,
                SPIKE_CONCURRENCY,
                SPIKE_P95_SLO_MS));

    String report = buildReport(checks);
    File reportFile = new File("../docs/bootcamp/evidence/p2-slo-verification.md");
    reportFile.getParentFile().mkdirs();
    Files.writeString(reportFile.toPath(), report);

    checks.forEach(
        check -> {
          assertThat(check.result().p95()).isLessThan(check.sloMs());
          assertThat(check.result().errorRate()).isEqualTo(0.0);
        });
  }

  private LoadResult runScenario(
      String name, int concurrentUsers, Supplier<HttpRequest> requestFactory) throws Exception {
    return new LoadTest(httpClient, name, requestFactory)
        .run(concurrentUsers, RAMP_UP_SECONDS, DURATION_SECONDS);
  }

  private HttpRequest listRequest() {
    return HttpRequest.newBuilder(
            URI.create(baseUrl() + "?page=" + LIST_PAGE + "&size=" + LIST_SIZE))
        .GET()
        .build();
  }

  private HttpRequest byIdRequest() {
    int id = ThreadLocalRandom.current().nextInt(MIN_PRODUCT_ID, MAX_PRODUCT_ID_EXCLUSIVE);
    return HttpRequest.newBuilder(URI.create(baseUrl() + "/" + id)).GET().build();
  }

  private HttpRequest searchRequest() {
    return HttpRequest.newBuilder(
            URI.create(
                baseUrl() + "?query=" + SEARCH_QUERY + "&page=" + LIST_PAGE + "&size=" + LIST_SIZE))
        .GET()
        .build();
  }

  private String buildReport(List<SloCheck> checks) {
    StringBuilder builder = new StringBuilder();
    builder.append("# Phase 2 — SLO Verification Report").append('\n');
    builder.append('\n');
    builder
        .append("Environment: local Docker PostgreSQL, JDK 21, Spring Boot, 1,000 seeded products.")
        .append('\n');
    builder
        .append("Load test: ramp-up " + RAMP_UP_SECONDS + "s, duration " + DURATION_SECONDS + "s.")
        .append('\n');
    builder.append('\n');
    builder
        .append("| SLO | Endpoint | Concurrency | p95 (ms) | RPS | Error rate | Result |")
        .append('\n');
    builder.append("|---|---|---:|---:|---:|---:|---|").append('\n');
    for (SloCheck check : checks) {
      boolean passed = check.result().p95() < check.sloMs() && check.result().errorRate() == 0.0;
      builder
          .append(
              "| "
                  + check.name()
                  + " | "
                  + check.endpoint()
                  + " | "
                  + check.concurrency()
                  + " | "
                  + format(check.result().p95(), 2)
                  + " | "
                  + format(check.result().throughputRps(), 2)
                  + " | "
                  + format(check.result().errorRate(), 2)
                  + " | "
                  + (passed ? "PASS" : "FAIL")
                  + " |")
          .append('\n');
    }
    return builder.toString();
  }

  private static String format(double value, int digits) {
    return String.format("%." + digits + "f", value);
  }

  private record SloCheck(
      String name, String endpoint, LoadResult result, int concurrency, double sloMs) {}
}
