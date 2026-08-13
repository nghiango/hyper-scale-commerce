package com.hyperscale.commerce.modules.catalog.domain

data class ProductId(val value: Long) {
  init {
    require(value > 0) { "Product ID must be positive" }
  }
}
