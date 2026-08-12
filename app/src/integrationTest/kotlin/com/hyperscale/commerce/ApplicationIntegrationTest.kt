package com.hyperscale.commerce

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
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationIntegrationTest
@Autowired
constructor(
    private val jdbcTemplate: JdbcTemplate,
    @param:Value("\${local.server.port}") private val port: Int,
) {

  private val httpClient = HttpClient.newHttpClient()

  companion object {
    @Container
    @JvmStatic
    @ServiceConnection
    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
  }

  @Test
  fun `connects to the isolated container database`() {
    assertThat(jdbcTemplate.queryForObject("SELECT 1", Int::class.java)).isEqualTo(1)
  }

  @Test
  fun `flyway baseline migration is applied`() {
    val version =
        jdbcTemplate.queryForObject(
            "SELECT max(version) FROM flyway_schema_history WHERE success",
            String::class.java,
        )
    assertThat(version).isEqualTo("1")
  }

  @Test
  fun `liveness and readiness probes are up`() {
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

  private fun get(path: String): HttpResponse<String> {
    val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }
}
