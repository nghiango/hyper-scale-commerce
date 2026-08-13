package com.hyperscale.commerce.modules.catalog.domain

interface ProductRepository {
  fun findById(id: ProductId): Product?

  fun findBySku(sku: Sku): Product?

  fun search(query: String?, page: Int, size: Int): List<Product>

  fun count(query: String?): Long
}
