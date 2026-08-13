package com.hyperscale.commerce.modules.catalog.application

import com.hyperscale.commerce.modules.catalog.domain.ProductId
import com.hyperscale.commerce.modules.catalog.domain.ProductNotFoundException
import com.hyperscale.commerce.modules.catalog.domain.ProductRepository
import com.hyperscale.commerce.modules.catalog.domain.Sku
import org.springframework.stereotype.Service

@Service
class CatalogService(private val productRepository: ProductRepository) {
  fun listProducts(query: String?, page: Int, size: Int): PagedProductsDto {
    require(page >= MIN_PAGE) { "Page must not be negative" }
    require(size in MIN_SIZE..MAX_SIZE) { "Size must be between $MIN_SIZE and $MAX_SIZE" }

    val total = productRepository.count(query)
    val products = productRepository.search(query, page, size)
    return PagedProductsDto(
        total = total,
        items = products.map { it.toDto() },
    )
  }

  fun getProductById(id: Long): ProductDto {
    val product = productRepository.findById(ProductId(id))
    return product?.toDto() ?: throw ProductNotFoundException("Product with id $id not found")
  }

  fun getProductBySku(sku: String): ProductDto {
    val product = productRepository.findBySku(Sku(sku))
    return product?.toDto() ?: throw ProductNotFoundException("Product with SKU $sku not found")
  }

  private fun com.hyperscale.commerce.modules.catalog.domain.Product.toDto(): ProductDto =
      ProductDto(
          id = id.value,
          sku = sku.value,
          name = name,
          description = description,
          price = price.amount,
          availability = availability.name,
      )

  companion object {
    private const val MIN_PAGE = 0
    private const val MIN_SIZE = 1
    private const val MAX_SIZE = 100
  }
}
