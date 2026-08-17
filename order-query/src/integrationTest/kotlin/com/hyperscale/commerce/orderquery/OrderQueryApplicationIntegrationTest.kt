package com.hyperscale.commerce.orderquery

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderQueryApplicationIntegrationTest
@Autowired
constructor(
    private val jdbcTemplate: JdbcTemplate,
    @param:Value("\${local.server.port}") private val port: Int,
) {

  private val httpClient = HttpClient.newHttpClient()

  companion object {
    @Container
    @JvmStatic
    val kafka: KafkaContainer = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))

    @Container
    @JvmStatic
    @ServiceConnection
    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

    @JvmStatic
    @DynamicPropertySource
    fun kafkaProperties(registry: DynamicPropertyRegistry) {
      registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
    }
  }

  @Test
  fun `connects to the isolated container database`() {
    assertThat(jdbcTemplate.queryForObject("SELECT 1", Int::class.java)).isEqualTo(1)
  }

  @Test
  fun `flyway applies the order_query read model migration`() {
    val version =
        jdbcTemplate.queryForObject(
            "SELECT max(version) FROM order_query.flyway_schema_history WHERE success",
            String::class.java,
        )
    assertThat(version).isEqualTo("1")

    val table =
        jdbcTemplate.queryForObject(
            "SELECT to_regclass('order_query.order_read_model')",
            String::class.java,
        )
    assertThat(table).isEqualTo("order_query.order_read_model")
  }

  @Test
  fun `liveness and readiness probes are up without the monolith`() {
    val liveness = get("/actuator/health/liveness")
    val readiness = get("/actuator/health/readiness")

    assertThat(liveness.statusCode()).isEqualTo(200)
    assertThat(liveness.body()).contains("\"UP\"")
    assertThat(readiness.statusCode()).isEqualTo(200)
    assertThat(readiness.body()).contains("\"UP\"")
  }

  @Test
  fun `openapi document is generated`() {
    val response = get("/v3/api-docs")

    assertThat(response.statusCode()).isEqualTo(200)
    assertThat(response.body()).contains("\"openapi\"")
  }

  @Test
  fun `event pipeline metrics are exposed`() {
    val response = get("/actuator/prometheus")

    assertThat(response.statusCode()).isEqualTo(200)
    assertThat(response.body())
        .contains(
            "events_consumed_total",
            "events_dlq_total",
            "kafka_consumer_lag",
            "order_read_model_lag_seconds",
            "consumer=\"order-query\"",
        )
  }

  @Test
  fun `sensitive actuator endpoints are not exposed`() {
    val env = get("/actuator/env")
    val beans = get("/actuator/beans")
    val heapdump = get("/actuator/heapdump")
    val info = get("/actuator/info")

    assertThat(env.statusCode()).isEqualTo(404)
    assertThat(beans.statusCode()).isEqualTo(404)
    assertThat(heapdump.statusCode()).isEqualTo(404)
    assertThat(info.statusCode()).isEqualTo(200)
  }

  private fun get(path: String): HttpResponse<String> {
    val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }
}
