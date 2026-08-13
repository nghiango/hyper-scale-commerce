package com.hyperscale.commerce.modules.catalog.domain

data class Product(
    val id: ProductId,
    val sku: Sku,
    val name: String,
    val description: String?,
    val price: Money,
    val availability: Availability,
) {
  init {
    require(name.isNotBlank()) { "Product name must not be blank" }
    require(name.length <= MAX_NAME_LENGTH) {
      "Product name must not exceed $MAX_NAME_LENGTH characters"
    }
  }

  companion object {
    private const val MAX_NAME_LENGTH = 255
  }
}
