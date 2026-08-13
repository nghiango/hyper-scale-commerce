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
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogControllerIntegrationTest
@Autowired
constructor(
    @LocalServerPort private val port: Int,
    private val jdbcTemplate: JdbcTemplate,
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
  fun `lists products with pagination`() {
    seedProduct(sku = "LIST-1", name = "Listable Product")

    val response = get("$baseUrl?page=0&size=10")

    assertThat(response.statusCode()).isEqualTo(200)
    assertThat(response.body()).contains("Listable Product")
  }

  @Test
  fun `lists products with optional search`() {
    seedProduct(sku = "SEARCH-1", name = "Searchable Product")
    seedProduct(sku = "SEARCH-2", name = "Other Product")

    val response = get("$baseUrl?query=Searchable")

    assertThat(response.statusCode()).isEqualTo(200)
    assertThat(response.body()).contains("Searchable Product")
    assertThat(response.body()).doesNotContain("Other Product")
  }

  @Test
  fun `rejects invalid page parameter`() {
    val response = get("$baseUrl?page=-1&size=10")

    assertThat(response.statusCode()).isEqualTo(400)
  }

  @Test
  fun `rejects invalid size parameter`() {
    val response = get("$baseUrl?page=0&size=0")

    assertThat(response.statusCode()).isEqualTo(400)
  }

  @Test
  fun `finds product by id`() {
    val id = seedProduct(sku = "BY-ID-1", name = "By ID Product")

    val response = get("$baseUrl/$id")

    assertThat(response.statusCode()).isEqualTo(200)
    assertThat(response.body()).contains("By ID Product")
  }

  @Test
  fun `returns 404 for unknown product id`() {
    val response = get("$baseUrl/${Long.MAX_VALUE}")

    assertThat(response.statusCode()).isEqualTo(404)
  }

  @Test
  fun `finds product by sku`() {
    seedProduct(sku = "UNIQUE-SKU", name = "By SKU Product")

    val response = get("$baseUrl/sku/UNIQUE-SKU")

    assertThat(response.statusCode()).isEqualTo(200)
    assertThat(response.body()).contains("By SKU Product")
  }

  @Test
  fun `returns 404 for unknown sku`() {
    val response = get("$baseUrl/sku/UNKNOWN")

    assertThat(response.statusCode()).isEqualTo(404)
  }

  @Test
  fun `returns product availability`() {
    val id = seedProduct(sku = "AVAIL-1", name = "Availability Product")

    val response = get("$baseUrl/$id/availability")

    assertThat(response.statusCode()).isEqualTo(200)
    assertThat(response.body()).contains("IN_STOCK")
  }

  @Test
  fun `returns 404 availability for unknown product`() {
    val response = get("$baseUrl/${Long.MAX_VALUE}/availability")

    assertThat(response.statusCode()).isEqualTo(404)
  }

  @Test
  fun `openapi document contains catalog paths`() {
    val response = get("http://localhost:$port/v3/api-docs")

    assertThat(response.statusCode()).isEqualTo(200)
    assertThat(response.body()).contains("/catalog/products")
  }

  private fun get(url: String): HttpResponse<String> {
    val request = HttpRequest.newBuilder(URI(url)).GET().build()
    return client.send(request, BodyHandlers.ofString())
  }

  private fun seedProduct(sku: String, name: String): Long {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO catalog.products (sku, name, description, price, availability, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, now(), now())
        RETURNING id
        """
            .trimIndent(),
        Long::class.java,
        sku,
        name,
        "A test product",
        9999,
        "IN_STOCK",
    )!!
  }
}
