package com.hyperscale.commerce.modules.catalog.infrastructure

import com.hyperscale.commerce.modules.catalog.domain.ProductId
import com.hyperscale.commerce.modules.catalog.domain.ProductRepository
import com.hyperscale.commerce.modules.catalog.domain.Sku
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JdbcProductRepositoryIntegrationTest
@Autowired
constructor(
    private val repository: ProductRepository,
    private val jdbcTemplate: JdbcTemplate,
) {

  companion object {
    @Container
    @JvmStatic
    @ServiceConnection
    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
  }

  @Test
  fun `finds product by id`() {
    val id = seedProduct(sku = "FIND-BY-ID", name = "Find By ID")

    val product = repository.findById(ProductId(id))

    assertThat(product).isNotNull
    assertThat(product?.sku?.value).isEqualTo("FIND-BY-ID")
    assertThat(product?.name).isEqualTo("Find By ID")
  }

  @Test
  fun `finds product by sku`() {
    seedProduct(sku = "FIND-BY-SKU", name = "Find By SKU")

    val product = repository.findBySku(Sku("FIND-BY-SKU"))

    assertThat(product).isNotNull
    assertThat(product?.name).isEqualTo("Find By SKU")
  }

  @Test
  fun `returns null for unknown product`() {
    val product = repository.findById(ProductId(Long.MAX_VALUE))

    assertThat(product).isNull()
  }

  @Test
  fun `searches products by name`() {
    seedProduct(sku = "SEARCH-1", name = "Unique Searchable Product")
    seedProduct(sku = "SEARCH-2", name = "Another Product")

    val result = repository.search(query = "Searchable", page = 0, size = 10)

    assertThat(result).hasSize(1)
    assertThat(result.first().name).isEqualTo("Unique Searchable Product")
  }

  @Test
  fun `searches products by sku`() {
    seedProduct(sku = "UNIQUE-SKU-123", name = "By SKU")

    val result = repository.search(query = "UNIQUE-SKU", page = 0, size = 10)

    assertThat(result).hasSize(1)
    assertThat(result.first().sku.value).isEqualTo("UNIQUE-SKU-123")
  }

  @Test
  fun `paginates search results`() {
    seedProduct(sku = "PAGE-1", name = "Page One")
    seedProduct(sku = "PAGE-2", name = "Page Two")

    val firstPage = repository.search(query = "Page", page = 0, size = 1)
    val secondPage = repository.search(query = "Page", page = 1, size = 1)

    assertThat(firstPage).hasSize(1)
    assertThat(secondPage).hasSize(1)
    assertThat(firstPage.first().sku.value).isNotEqualTo(secondPage.first().sku.value)
  }

  @Test
  fun `counts products matching query`() {
    seedProduct(sku = "COUNT-1", name = "Count Product Alpha")
    seedProduct(sku = "COUNT-2", name = "Count Product Beta")
    seedProduct(sku = "COUNT-3", name = "Something Else")

    val count = repository.count(query = "Count Product")

    assertThat(count).isEqualTo(2)
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
