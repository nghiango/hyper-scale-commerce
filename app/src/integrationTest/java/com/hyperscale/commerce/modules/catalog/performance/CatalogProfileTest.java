package com.hyperscale.commerce.modules.catalog.performance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class CatalogProfileTest {

  private static final int REQUEST_COUNT = 20;
  private static final int MIN_PRODUCT_ID = 1;
  private static final int MAX_PRODUCT_ID_EXCLUSIVE = 1001;
  private static final int LIST_PAGE = 0;
  private static final int LIST_SIZE = 20;
  private static final String SEARCH_QUERY = "Product";
  private static final int HTTP_TIMING_SAMPLE_COUNT = 10;
  private static final int METRIC_SAMPLE_COUNT = 3;

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

  private final int port;
  private final JdbcTemplate jdbcTemplate;
  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Autowired
  public CatalogProfileTest(@LocalServerPort int port, JdbcTemplate jdbcTemplate) {
    this.port = port;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Test
  @DisplayName("captures profile evidence and writes p2-profile report")
  void capturesProfileEvidenceAndWritesP2ProfileReport() throws Exception {
    List<ExplainSection> explainSections =
        List.of(
            new ExplainSection(
                "findById",
                explain(
                    "EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM catalog.products WHERE id = ?",
                    MIN_PRODUCT_ID)),
            new ExplainSection(
                "findBySku",
                explain(
                    "EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM catalog.products WHERE sku = ?",
                    "PERF-SKU-00001")),
            new ExplainSection(
                "search",
                explain(
                    "EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM catalog.products WHERE name ILIKE ? OR sku ILIKE ? ORDER BY id LIMIT ? OFFSET ?",
                    "%" + SEARCH_QUERY + "%",
                    "%" + SEARCH_QUERY + "%",
                    LIST_SIZE,
                    LIST_PAGE)),
            new ExplainSection(
                "count",
                explain(
                    "EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM catalog.products WHERE name ILIKE ? OR sku ILIKE ?",
                    "%" + SEARCH_QUERY + "%",
                    "%" + SEARCH_QUERY + "%")));

    exerciseEndpoints();

    String prometheus = get("/actuator/prometheus").body();
    List<String> httpTimings =
        prometheus
            .lines()
            .filter(
                line ->
                    line.contains("http_server_requests_seconds")
                        && line.contains("/catalog/products"))
            .limit(HTTP_TIMING_SAMPLE_COUNT)
            .toList();
    List<String> jvmMetrics =
        prometheus
            .lines()
            .filter(line -> line.startsWith("jvm_memory_used_bytes"))
            .limit(METRIC_SAMPLE_COUNT)
            .toList();
    List<String> hikariMetrics =
        prometheus
            .lines()
            .filter(line -> line.startsWith("hikaricp_connections_"))
            .limit(METRIC_SAMPLE_COUNT)
            .toList();

    String report = buildReport(explainSections, httpTimings, jvmMetrics, hikariMetrics);
    File reportFile = new File("../docs/bootcamp/evidence/p2-profile.md");
    reportFile.getParentFile().mkdirs();
    Files.writeString(reportFile.toPath(), report);

    assertThat(reportFile).exists();
    assertThat(reportFile.length()).isPositive();
    assertThat(explainSections).allMatch(section -> !section.lines().isEmpty());
  }

  private List<String> explain(String sql, Object... args) {
    return jdbcTemplate.queryForList(sql, String.class, args).stream()
        .filter(Objects::nonNull)
        .toList();
  }

  private void exerciseEndpoints() throws IOException, InterruptedException {
    for (int i = 0; i < REQUEST_COUNT; i++) {
      get("/catalog/products?page=" + LIST_PAGE + "&size=" + LIST_SIZE);
      get("/catalog/products/" + randomId());
      get("/catalog/products?query=" + SEARCH_QUERY + "&page=" + LIST_PAGE + "&size=" + LIST_SIZE);
      get("/catalog/products/" + randomId() + "/availability");
    }
  }

  private int randomId() {
    return ThreadLocalRandom.current().nextInt(MIN_PRODUCT_ID, MAX_PRODUCT_ID_EXCLUSIVE);
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String buildReport(
      List<ExplainSection> explainSections,
      List<String> httpTimings,
      List<String> jvmMetrics,
      List<String> hikariMetrics) {
    StringBuilder builder = new StringBuilder();
    builder.append("# Phase 2 — Catalog Profile Report").append('\n');
    builder.append('\n');
    builder
        .append("Environment: local Docker PostgreSQL, JDK 21, Spring Boot, 1,000 seeded products.")
        .append('\n');
    builder.append('\n');
    builder.append("## EXPLAIN ANALYZE").append('\n');
    for (ExplainSection section : explainSections) {
      builder.append('\n');
      builder.append("### " + section.name()).append('\n');
      builder.append("```").append('\n');
      section.lines().forEach(line -> builder.append(line).append('\n'));
      builder.append("```").append('\n');
    }
    builder.append('\n');
    builder.append("## Micrometer HTTP timings (sample)").append('\n');
    httpTimings.forEach(timing -> builder.append("- `" + timing + "`").append('\n'));
    builder.append('\n');
    builder.append("## JVM metrics (sample)").append('\n');
    jvmMetrics.forEach(metric -> builder.append("- `" + metric + "`").append('\n'));
    builder.append('\n');
    builder.append("## Hikari pool metrics (sample)").append('\n');
    hikariMetrics.forEach(metric -> builder.append("- `" + metric + "`").append('\n'));
    builder.append('\n');
    builder.append("## Bottleneck analysis").append('\n');
    builder.append(bottleneckAnalysis(explainSections)).append('\n');
    return builder.toString();
  }

  private String bottleneckAnalysis(List<ExplainSection> explainSections) {
    String searchExplain = String.join("\n", findSection(explainSections, "search").lines());
    String countExplain = String.join("\n", findSection(explainSections, "count").lines());
    if (searchExplain.contains("Seq Scan") || countExplain.contains("Seq Scan")) {
      return "The search and count queries use a sequential scan on catalog.products because ILIKE '%...%' cannot use the existing B-tree indexes. This is the primary bottleneck and will degrade as the catalog grows.";
    } else {
      return "No sequential scans detected; all queries appear to use indexes.";
    }
  }

  private ExplainSection findSection(List<ExplainSection> sections, String name) {
    return sections.stream()
        .filter(section -> section.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private record ExplainSection(String name, List<String> lines) {}
}
