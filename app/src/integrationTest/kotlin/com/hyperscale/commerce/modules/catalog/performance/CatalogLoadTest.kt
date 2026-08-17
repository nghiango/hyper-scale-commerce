package com.hyperscale.commerce.modules.catalog.performance

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
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

private const val CONCURRENT_USERS = 10
private const val RAMP_UP_SECONDS = 1
private const val DURATION_SECONDS = 5
private const val MAX_PRODUCT_ID = 1000
private const val NANOS_PER_MILLISECOND = 1_000_000.0
private const val HTTP_OK = 200
private const val REQUEST_FAILED = -1
private const val PERCENTILE_50 = 0.50
private const val PERCENTILE_95 = 0.95
private const val PERCENTILE_99 = 0.99
private const val LIST_PAGE = 0
private const val LIST_SIZE = 20
private const val SEARCH_QUERY = "Product"
private const val SKU_PREFIX = "PERF-SKU-"

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.rate-limiting.enabled=false"],
)
class CatalogLoadTest @Autowired constructor(@LocalServerPort private val port: Int) {

  companion object {
    @Container
    @JvmStatic
    @ServiceConnection
    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
  }

  private val baseUrl: String
    get() = "http://localhost:$port/catalog/products"

  @Test
  fun `produces a baseline report for catalog endpoints`() {
    val httpClient = HttpClient.newHttpClient()

    val scenarios =
        listOf(
            LoadScenario(
                "GET /catalog/products?page=$LIST_PAGE&size=$LIST_SIZE", { listRequest() }),
            LoadScenario("GET /catalog/products/{id}", { byIdRequest() }),
            LoadScenario(
                "GET /catalog/products?query=$SEARCH_QUERY&page=$LIST_PAGE&size=$LIST_SIZE",
                { searchRequest() }),
            LoadScenario("GET /catalog/products/{id}/availability", { availabilityRequest() }),
        )

    val results =
        scenarios.map { scenario ->
          LoadTest(httpClient, scenario.name, scenario.requestFactory)
              .run(CONCURRENT_USERS, RAMP_UP_SECONDS, DURATION_SECONDS)
        }

    val report = buildMarkdownReport(results)
    val reportFile = File("../docs/bootcamp/evidence/p2-baseline.md")
    reportFile.parentFile.mkdirs()
    reportFile.writeText(report)

    results.forEach { result ->
      assertThat(result.throughputRps).isPositive()
      assertThat(result.errorRate).isEqualTo(0.0)
    }
  }

  private fun listRequest(): HttpRequest {
    return HttpRequest.newBuilder(URI("$baseUrl?page=$LIST_PAGE&size=$LIST_SIZE")).GET().build()
  }

  private fun byIdRequest(): HttpRequest {
    val id = ThreadLocalRandom.current().nextInt(1, MAX_PRODUCT_ID + 1)
    return HttpRequest.newBuilder(URI("$baseUrl/$id")).GET().build()
  }

  private fun searchRequest(): HttpRequest {
    return HttpRequest.newBuilder(
            URI("$baseUrl?query=$SEARCH_QUERY&page=$LIST_PAGE&size=$LIST_SIZE"))
        .GET()
        .build()
  }

  private fun availabilityRequest(): HttpRequest {
    val id = ThreadLocalRandom.current().nextInt(1, MAX_PRODUCT_ID + 1)
    return HttpRequest.newBuilder(URI("$baseUrl/$id/availability")).GET().build()
  }

  private fun buildMarkdownReport(results: List<LoadResult>): String {
    val builder = StringBuilder()
    builder.appendLine("# Phase 2 — Catalog API Baseline Report")
    builder.appendLine()
    builder.appendLine(
        "Environment: local Docker PostgreSQL, JDK 21, Spring Boot, 1,000 seeded products.")
    builder.appendLine(
        "Concurrency: $CONCURRENT_USERS users, ramp-up: ${RAMP_UP_SECONDS}s, duration: ${DURATION_SECONDS}s.")
    builder.appendLine()
    builder.appendLine(
        "| Endpoint | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (RPS) | Error rate |")
    builder.appendLine("|---|---:|---:|---:|---:|---:|")

    results.forEach { result ->
      builder.appendLine(
          "| ${result.name} | ${result.p50.format(2)} | ${result.p95.format(2)} | ${result.p99.format(2)} | ${result.throughputRps.format(2)} | ${result.errorRate.format(2)} |")
    }

    builder.appendLine()
    builder.appendLine("Raw totals:")
    results.forEach { result ->
      builder.appendLine(
          "- ${result.name}: ${result.totalRequests} total, ${result.successfulRequests} successful, ${result.failedRequests} failed")
    }

    return builder.toString()
  }

  private fun Double.format(digits: Int): String = String.format("%.${digits}f", this)
}

internal data class LoadScenario(val name: String, val requestFactory: () -> HttpRequest)

internal data class LoadSample(val statusCode: Int, val latencyMs: Double)

internal data class LoadResult(
    val name: String,
    val totalRequests: Int,
    val successfulRequests: Int,
    val failedRequests: Int,
    val p50: Double,
    val p95: Double,
    val p99: Double,
    val throughputRps: Double,
    val errorRate: Double,
)

internal class LoadTest(
    private val httpClient: HttpClient,
    private val name: String,
    private val requestFactory: () -> HttpRequest,
) {

  fun run(concurrentUsers: Int, rampUpSeconds: Int, durationSeconds: Int): LoadResult {
    val executor = Executors.newFixedThreadPool(concurrentUsers)
    val totalDurationSeconds = rampUpSeconds + durationSeconds
    val testEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(totalDurationSeconds.toLong())
    val rampUpNanos = TimeUnit.SECONDS.toNanos(rampUpSeconds.toLong())
    val delayPerWorker = if (concurrentUsers > 1) rampUpNanos / (concurrentUsers - 1) else 0L

    val callables =
        (0 until concurrentUsers).map { workerId ->
          Callable<List<LoadSample>> {
            val workerStartDelay = workerId * delayPerWorker
            if (workerStartDelay > 0) {
              TimeUnit.NANOSECONDS.sleep(workerStartDelay)
            }

            val samples = mutableListOf<LoadSample>()
            while (System.nanoTime() < testEnd) {
              val request = requestFactory()
              val start = System.nanoTime()
              val statusCode =
                  try {
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
                  } catch (_: Exception) {
                    REQUEST_FAILED
                  }
              val end = System.nanoTime()
              val latencyMs = (end - start).toDouble() / NANOS_PER_MILLISECOND
              samples.add(LoadSample(statusCode, latencyMs))
            }

            samples
          }
        }

    val futures = executor.invokeAll(callables)
    val allSamples = futures.flatMap { it.get() }

    executor.shutdown()
    executor.awaitTermination(1, TimeUnit.MINUTES)

    val totalRequests = allSamples.size
    val successfulRequests = allSamples.count { it.statusCode == HTTP_OK }
    val failedRequests = totalRequests - successfulRequests
    val latencies = allSamples.filter { it.statusCode == HTTP_OK }.map { it.latencyMs }.sorted()

    val p50 = percentile(latencies, PERCENTILE_50)
    val p95 = percentile(latencies, PERCENTILE_95)
    val p99 = percentile(latencies, PERCENTILE_99)
    val throughputRps = if (durationSeconds > 0) totalRequests.toDouble() / durationSeconds else 0.0
    val errorRate = if (totalRequests > 0) failedRequests.toDouble() / totalRequests else 0.0

    return LoadResult(
        name = name,
        totalRequests = totalRequests,
        successfulRequests = successfulRequests,
        failedRequests = failedRequests,
        p50 = p50,
        p95 = p95,
        p99 = p99,
        throughputRps = throughputRps,
        errorRate = errorRate,
    )
  }

  private fun percentile(sorted: List<Double>, fraction: Double): Double {
    if (sorted.isEmpty()) {
      return 0.0
    }
    val index = ceil(fraction * sorted.size).toInt().coerceAtMost(sorted.size) - 1
    return sorted[index]
  }
}
