package com.hyperscale.commerce.modules.catalog.application

import com.hyperscale.commerce.modules.catalog.domain.Availability
import com.hyperscale.commerce.modules.catalog.domain.Money
import com.hyperscale.commerce.modules.catalog.domain.Product
import com.hyperscale.commerce.modules.catalog.domain.ProductId
import com.hyperscale.commerce.modules.catalog.domain.ProductNotFoundException
import com.hyperscale.commerce.modules.catalog.domain.ProductRepository
import com.hyperscale.commerce.modules.catalog.domain.Sku
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CatalogServiceTest {

  @Test
  fun `lists products with pagination`() {
    val fake = FakeProductRepository().apply { add(product(id = 1)) }
    val service = CatalogService(fake)

    val result = service.listProducts(query = null, page = 0, size = 10)

    assertThat(result.total).isEqualTo(1)
    assertThat(result.items).hasSize(1)
    assertThat(result.items.first().id).isEqualTo(1)
  }

  @Test
  fun `rejects negative page`() {
    val service = CatalogService(FakeProductRepository())

    assertThatThrownBy { service.listProducts(query = null, page = -1, size = 10) }
        .isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun `rejects invalid size`() {
    val service = CatalogService(FakeProductRepository())

    assertThatThrownBy { service.listProducts(query = null, page = 0, size = 0) }
        .isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun `finds product by id`() {
    val fake = FakeProductRepository().apply { add(product(id = 1)) }
    val service = CatalogService(fake)

    val result = service.getProductById(1)

    assertThat(result.sku).isEqualTo("SKU-1")
  }

  @Test
  fun `throws when product not found by id`() {
    val service = CatalogService(FakeProductRepository())

    assertThatThrownBy { service.getProductById(99) }
        .isInstanceOf(ProductNotFoundException::class.java)
  }

  @Test
  fun `finds product by sku`() {
    val fake = FakeProductRepository().apply { add(product(id = 2)) }
    val service = CatalogService(fake)

    val result = service.getProductBySku("SKU-2")

    assertThat(result.id).isEqualTo(2)
  }

  @Test
  fun `throws when product not found by sku`() {
    val service = CatalogService(FakeProductRepository())

    assertThatThrownBy { service.getProductBySku("MISSING") }
        .isInstanceOf(ProductNotFoundException::class.java)
  }

  private fun product(id: Long): Product =
      Product(
          id = ProductId(id),
          sku = Sku("SKU-$id"),
          name = "Product $id",
          description = null,
          price = Money(10000),
          availability = Availability.IN_STOCK,
      )

  private class FakeProductRepository : ProductRepository {
    private val products = mutableListOf<Product>()

    fun add(product: Product) {
      products.add(product)
    }

    override fun findById(id: ProductId): Product? = products.find { it.id == id }

    override fun findBySku(sku: Sku): Product? = products.find { it.sku == sku }

    override fun search(query: String?, page: Int, size: Int): List<Product> =
        products
            .filter { query == null || it.name.contains(query) || it.sku.value.contains(query) }
            .drop(page * size)
            .take(size)

    override fun count(query: String?): Long =
        products
            .count { query == null || it.name.contains(query) || it.sku.value.contains(query) }
            .toLong()
  }
}
