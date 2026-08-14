package com.hyperscale.commerce.modules.catalog.performance

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ThreadLocalRandom
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

private const val REQUEST_COUNT = 20
private const val MIN_PRODUCT_ID = 1
private const val MAX_PRODUCT_ID_EXCLUSIVE = 1001
private const val LIST_PAGE = 0
private const val LIST_SIZE = 20
private const val SEARCH_QUERY = "Product"
private const val HTTP_TIMING_SAMPLE_COUNT = 10
private const val METRIC_SAMPLE_COUNT = 3

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogProfileTest
@Autowired
constructor(
    @param:LocalServerPort private val port: Int,
    private val jdbcTemplate: JdbcTemplate,
) {

  companion object {
    @Container
    @JvmStatic
    @ServiceConnection
    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
  }

  private val httpClient = HttpClient.newHttpClient()

  @Test
  fun `captures profile evidence and writes p2-profile report`() {
    val explainSections =
        listOf(
            "findById" to
                explain(
                    "EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM catalog.products WHERE id = ?",
                    MIN_PRODUCT_ID),
            "findBySku" to
                explain(
                    "EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM catalog.products WHERE sku = ?",
                    "PERF-SKU-00001"),
            "search" to
                explain(
                    "EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM catalog.products WHERE name ILIKE ? OR sku ILIKE ? ORDER BY id LIMIT ? OFFSET ?",
                    "%$SEARCH_QUERY%",
                    "%$SEARCH_QUERY%",
                    LIST_SIZE,
                    LIST_PAGE),
            "count" to
                explain(
                    "EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM catalog.products WHERE name ILIKE ? OR sku ILIKE ?",
                    "%$SEARCH_QUERY%",
                    "%$SEARCH_QUERY%"),
        )

    exerciseEndpoints()

    val prometheus = get("/actuator/prometheus").body()
    val httpTimings =
        prometheus
            .lines()
            .filter {
              it.contains("http_server_requests_seconds") && it.contains("/catalog/products")
            }
            .take(HTTP_TIMING_SAMPLE_COUNT)
            .toList()
    val jvmMetrics =
        prometheus
            .lines()
            .filter { it.startsWith("jvm_memory_used_bytes") }
            .take(METRIC_SAMPLE_COUNT)
            .toList()
    val hikariMetrics =
        prometheus
            .lines()
            .filter { it.startsWith("hikaricp_connections_") }
            .take(METRIC_SAMPLE_COUNT)
            .toList()

    val report = buildReport(explainSections, httpTimings, jvmMetrics, hikariMetrics)
    val reportFile = File("../docs/bootcamp/evidence/p2-profile.md")
    reportFile.parentFile.mkdirs()
    reportFile.writeText(report)

    assertThat(reportFile).exists()
    assertThat(reportFile.length()).isPositive()
    assertThat(explainSections).allMatch { it.second.isNotEmpty() }
  }

  private fun explain(sql: String, vararg args: Any): List<String> {
    return jdbcTemplate.queryForList(sql, String::class.java, *args).mapNotNull { it }
  }

  private fun exerciseEndpoints() {
    repeat(REQUEST_COUNT) {
      get("/catalog/products?page=$LIST_PAGE&size=$LIST_SIZE")
      get("/catalog/products/${randomId()}")
      get("/catalog/products?query=$SEARCH_QUERY&page=$LIST_PAGE&size=$LIST_SIZE")
      get("/catalog/products/${randomId()}/availability")
    }
  }

  private fun randomId(): Int =
      ThreadLocalRandom.current().nextInt(MIN_PRODUCT_ID, MAX_PRODUCT_ID_EXCLUSIVE)

  private fun get(path: String): HttpResponse<String> {
    val request = HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }

  private fun buildReport(
      explainSections: List<Pair<String, List<String>>>,
      httpTimings: List<String>,
      jvmMetrics: List<String>,
      hikariMetrics: List<String>,
  ): String {
    val builder = StringBuilder()
    builder.appendLine("# Phase 2 — Catalog Profile Report")
    builder.appendLine()
    builder.appendLine(
        "Environment: local Docker PostgreSQL, JDK 21, Spring Boot, 1,000 seeded products.")
    builder.appendLine()
    builder.appendLine("## EXPLAIN ANALYZE")
    explainSections.forEach { (name, lines) ->
      builder.appendLine()
      builder.appendLine("### $name")
      builder.appendLine("```")
      lines.forEach { builder.appendLine(it) }
      builder.appendLine("```")
    }
    builder.appendLine()
    builder.appendLine("## Micrometer HTTP timings (sample)")
    httpTimings.forEach { builder.appendLine("- `$it`") }
    builder.appendLine()
    builder.appendLine("## JVM metrics (sample)")
    jvmMetrics.forEach { builder.appendLine("- `$it`") }
    builder.appendLine()
    builder.appendLine("## Hikari pool metrics (sample)")
    hikariMetrics.forEach { builder.appendLine("- `$it`") }
    builder.appendLine()
    builder.appendLine("## Bottleneck analysis")
    builder.appendLine(bottleneckAnalysis(explainSections))
    return builder.toString()
  }

  private fun bottleneckAnalysis(explainSections: List<Pair<String, List<String>>>): String {
    val searchExplain = explainSections.first { it.first == "search" }.second.joinToString("\n")
    val countExplain = explainSections.first { it.first == "count" }.second.joinToString("\n")
    return if (searchExplain.contains("Seq Scan") || countExplain.contains("Seq Scan")) {
      "The search and count queries use a sequential scan on catalog.products because ILIKE '%...%' cannot use the existing B-tree indexes. This is the primary bottleneck and will degrade as the catalog grows."
    } else {
      "No sequential scans detected; all queries appear to use indexes."
    }
  }
}
