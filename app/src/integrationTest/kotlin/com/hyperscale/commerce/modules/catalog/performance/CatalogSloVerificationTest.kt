package com.hyperscale.commerce.modules.catalog.performance

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.util.concurrent.ThreadLocalRandom
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

private const val RAMP_UP_SECONDS = 1
private const val DURATION_SECONDS = 5
private const val MIN_PRODUCT_ID = 1
private const val MAX_PRODUCT_ID_EXCLUSIVE = 1001
private const val LIST_PAGE = 0
private const val LIST_SIZE = 20
private const val SEARCH_QUERY = "Product"
private const val BY_ID_CONCURRENCY = 100
private const val LIST_CONCURRENCY = 100
private const val SEARCH_CONCURRENCY = 50
private const val SPIKE_CONCURRENCY = 500
private const val BY_ID_P95_SLO_MS = 100.0
private const val LIST_P95_SLO_MS = 200.0
private const val SEARCH_P95_SLO_MS = 300.0
private const val SPIKE_P95_SLO_MS = 300.0

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogSloVerificationTest
@Autowired
constructor(@param:LocalServerPort private val port: Int) {

  companion object {
    @Container
    @JvmStatic
    @ServiceConnection
    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
  }

  private val httpClient = HttpClient.newHttpClient()
  private val baseUrl: String
    get() = "http://localhost:$port/catalog/products"

  @Test
  fun `verifies phase 2 SLOs and writes p2-slo-verification report`() {
    val byId = runScenario("GET /catalog/products/{id}", BY_ID_CONCURRENCY) { byIdRequest() }
    val list =
        runScenario("GET /catalog/products?page=$LIST_PAGE&size=$LIST_SIZE", LIST_CONCURRENCY) {
          listRequest()
        }
    val search =
        runScenario(
            "GET /catalog/products?query=$SEARCH_QUERY&page=$LIST_PAGE&size=$LIST_SIZE",
            SEARCH_CONCURRENCY) {
              searchRequest()
            }
    val spike =
        runScenario("GET /catalog/products/{id} (5x spike)", SPIKE_CONCURRENCY) { byIdRequest() }

    val checks =
        listOf(
            SloCheck(
                "p95 < 100ms at 100 RPS",
                "GET /catalog/products/{id}",
                byId,
                BY_ID_CONCURRENCY,
                BY_ID_P95_SLO_MS),
            SloCheck(
                "p95 < 200ms at 100 RPS",
                "GET /catalog/products",
                list,
                LIST_CONCURRENCY,
                LIST_P95_SLO_MS),
            SloCheck(
                "p95 < 300ms at 50 RPS",
                "GET /catalog/products?query=Product",
                search,
                SEARCH_CONCURRENCY,
                SEARCH_P95_SLO_MS),
            SloCheck(
                "p95 < 300ms at 500 RPS",
                "GET /catalog/products/{id} (5x spike)",
                spike,
                SPIKE_CONCURRENCY,
                SPIKE_P95_SLO_MS),
        )

    val report = buildReport(checks)
    val reportFile = File("../docs/bootcamp/evidence/p2-slo-verification.md")
    reportFile.parentFile.mkdirs()
    reportFile.writeText(report)

    checks.forEach { check ->
      assertThat(check.result.p95).isLessThan(check.sloMs)
      assertThat(check.result.errorRate).isEqualTo(0.0)
    }
  }

  private fun runScenario(
      name: String,
      concurrentUsers: Int,
      requestFactory: () -> HttpRequest,
  ): LoadResult {
    return LoadTest(httpClient, name, requestFactory)
        .run(concurrentUsers, RAMP_UP_SECONDS, DURATION_SECONDS)
  }

  private fun listRequest(): HttpRequest {
    return HttpRequest.newBuilder(URI("$baseUrl?page=$LIST_PAGE&size=$LIST_SIZE")).GET().build()
  }

  private fun byIdRequest(): HttpRequest {
    val id = ThreadLocalRandom.current().nextInt(MIN_PRODUCT_ID, MAX_PRODUCT_ID_EXCLUSIVE)
    return HttpRequest.newBuilder(URI("$baseUrl/$id")).GET().build()
  }

  private fun searchRequest(): HttpRequest {
    return HttpRequest.newBuilder(
            URI("$baseUrl?query=$SEARCH_QUERY&page=$LIST_PAGE&size=$LIST_SIZE"))
        .GET()
        .build()
  }

  private fun buildReport(checks: List<SloCheck>): String {
    val builder = StringBuilder()
    builder.appendLine("# Phase 2 — SLO Verification Report")
    builder.appendLine()
    builder.appendLine(
        "Environment: local Docker PostgreSQL, JDK 21, Spring Boot, 1,000 seeded products.")
    builder.appendLine("Load test: ramp-up ${RAMP_UP_SECONDS}s, duration ${DURATION_SECONDS}s.")
    builder.appendLine()
    builder.appendLine("| SLO | Endpoint | Concurrency | p95 (ms) | RPS | Error rate | Result |")
    builder.appendLine("|---|---|---:|---:|---:|---:|---|")
    checks.forEach { check ->
      val passed = check.result.p95 < check.sloMs && check.result.errorRate == 0.0
      builder.appendLine(
          "| ${check.name} | ${check.endpoint} | ${check.concurrency} | ${check.result.p95.format(2)} | ${check.result.throughputRps.format(2)} | ${check.result.errorRate.format(2)} | ${if (passed) "PASS" else "FAIL"} |")
    }
    return builder.toString()
  }

  private fun Double.format(digits: Int): String = String.format("%.${digits}f", this)
}

private data class SloCheck(
    val name: String,
    val endpoint: String,
    val result: LoadResult,
    val concurrency: Int,
    val sloMs: Double,
)
