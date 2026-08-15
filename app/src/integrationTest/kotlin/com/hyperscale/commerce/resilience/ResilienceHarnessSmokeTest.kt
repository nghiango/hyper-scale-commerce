package com.hyperscale.commerce.resilience

import com.hyperscale.commerce.Application
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

private const val KAFKA_HOST_PORT = 29093
private const val KAFKA_CONTAINER_PORT = 9093
private const val POSTGRES_HOST_PORT = 5433
private const val POSTGRES_CONTAINER_PORT = 5432

@Testcontainers
class ResilienceHarnessSmokeTest {

  companion object {
    @Container
    @JvmStatic
    val kafka: KafkaContainer =
        KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1")).apply {
          setPortBindings(listOf("$KAFKA_HOST_PORT:$KAFKA_CONTAINER_PORT"))
        }

    @Container
    @JvmStatic
    val postgres: PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:16").apply {
          setPortBindings(listOf("$POSTGRES_HOST_PORT:$POSTGRES_CONTAINER_PORT"))
        }
  }

  @Test
  fun `harness stops and restarts kafka and the app recovers`() {
    val context = startMonolith()
    val port = context.environment.getProperty("local.server.port")!!.toInt()
    try {
      ResilienceHarness.awaitReadiness(port)
      ResilienceHarness.awaitHealthContains(port, "\"kafka\":{\"status\":\"UP\"}")

      ResilienceHarness.stopKafka(kafka)
      ResilienceHarness.awaitHealthContains(port, "\"kafka\":{\"status\":\"DOWN\"}")

      ResilienceHarness.startKafka(kafka)
      ResilienceHarness.awaitHealthContains(port, "\"kafka\":{\"status\":\"UP\"}")

      ResilienceHarness.writeEvidence(buildSection())
    } finally {
      context.close()
    }
  }

  private fun startMonolith(): ConfigurableApplicationContext {
    return SpringApplicationBuilder(Application::class.java)
        .run(
            "--spring.application.name=hyper-scale-commerce",
            "--app.name=hyper-scale-commerce",
            "--spring.datasource.url=${postgres.jdbcUrl}",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--spring.kafka.bootstrap-servers=${kafka.bootstrapServers}",
            "--spring.flyway.locations=classpath:db/migration",
            "--spring.flyway.schemas=public",
            "--server.port=0",
        )
  }

  private fun buildSection(): String {
    return buildString {
      appendLine("## P6-02 — Failure-injection test harness")
      appendLine()
      appendLine(
          "Environment: Testcontainers PostgreSQL 16 + Kafka 7.7.1 with fixed host ports, JDK 21, Spring Boot 4.0.")
      appendLine()
      appendLine("### First experiment: Kafka stop/start")
      appendLine()
      appendLine(
          "The monolith was started against shared containers; Kafka was stopped and restarted using the harness.")
      appendLine()
      appendLine("| Step | Result |")
      appendLine("|---|---|")
      appendLine("| Kafka UP before outage | health shows `kafka: UP` |")
      appendLine("| Kafka stopped | health shows `kafka: DOWN` |")
      appendLine("| Kafka restarted | health returns to `kafka: UP` |")
    }
  }
}
