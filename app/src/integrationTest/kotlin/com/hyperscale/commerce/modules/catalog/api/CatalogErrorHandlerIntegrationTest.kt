package com.hyperscale.commerce.modules.catalog.api

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogErrorHandlerIntegrationTest
@Autowired
constructor(
    @param:LocalServerPort private val port: Int,
) {

  private val client = HttpClient.newHttpClient()
  private val baseUrl: String
    get() = "http://localhost:$port/catalog/products"

  companion object {
    @Container
    @JvmStatic
    @ServiceConnection
    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
  }

  @Test
  fun `returns 404 with error body for unknown product`() {
    val response = get("$baseUrl/99999")

    assertThat(response.statusCode()).isEqualTo(404)
    assertThat(response.body()).contains("error")
  }

  @Test
  fun `returns 400 with error body for invalid page`() {
    val response = get("$baseUrl?page=-1&size=10")

    assertThat(response.statusCode()).isEqualTo(400)
    assertThat(response.body()).contains("error")
  }

  @Test
  fun `returns 500 with error body for unexpected errors`() {
    val response = get("http://localhost:$port/catalog/test/error")

    assertThat(response.statusCode()).isEqualTo(500)
    assertThat(response.body()).contains("error")
    assertThat(response.body()).contains("Internal server error")
  }

  private fun get(url: String): HttpResponse<String> {
    val request = HttpRequest.newBuilder(URI(url)).GET().build()
    return client.send(request, BodyHandlers.ofString())
  }
}
