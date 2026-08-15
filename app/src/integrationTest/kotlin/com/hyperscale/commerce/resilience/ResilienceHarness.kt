package com.hyperscale.commerce.resilience

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait

object ResilienceHarness {
  private val httpClient = HttpClient.newHttpClient()
  private const val DEFAULT_TIMEOUT_SECONDS = 30L
  private const val POLL_MILLIS = 500L

  fun stopKafka(kafka: KafkaContainer) {
    kafka.stop()
  }

  fun startKafka(kafka: KafkaContainer) {
    kafka.start()
  }

  fun stopPostgres(postgres: PostgreSQLContainer<*>) {
    postgres.stop()
  }

  fun startPostgres(postgres: PostgreSQLContainer<*>) {
    postgres.waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 1))
    postgres.start()
  }

  fun awaitReadiness(port: Int, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS) {
    awaitHealthContains(port, "\"UP\"", timeoutSeconds, path = "/actuator/health/readiness")
  }

  fun awaitHealthContains(
      port: Int,
      expected: String,
      timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
      path: String = "/actuator/health",
  ) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
    while (System.nanoTime() < deadline) {
      try {
        val response = get(port, path)
        if (response.body().contains(expected)) {
          return
        }
      } catch (_: Exception) {
        // dependency or service not yet accepting connections
      }
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError(
        "Health on port $port did not contain '$expected' within $timeoutSeconds seconds")
  }

  fun writeEvidence(section: String) {
    val reportFile = File("../docs/bootcamp/evidence/p6-resilience.md")
    reportFile.parentFile.mkdirs()
    val existing = if (reportFile.exists()) reportFile.readText() else ""
    val heading = section.lineSequence().first { it.startsWith("## ") }
    val updated =
        if (heading in existing) {
          val before = existing.substringBefore(heading).trim()
          val after = existing.substringAfter(heading)
          val nextHeading = after.indexOf("\n## ")
          val remainder = if (nextHeading >= 0) after.substring(nextHeading + 1) else ""
          val body = section.trimEnd() + "\n" + remainder
          if (before.isEmpty()) body else before + "\n\n" + body
        } else {
          existing.trimEnd() + "\n\n" + section.trimEnd() + "\n"
        }
    reportFile.writeText(updated)
  }

  private fun get(port: Int, path: String): HttpResponse<String> {
    val request = HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }
}
