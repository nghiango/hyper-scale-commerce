package com.hyperscale.commerce.modules.order.domain

data class OrderItem(val sku: String, val quantity: Int) {
  init {
    require(sku.isNotBlank()) { "SKU must not be blank" }
    require(quantity > 0) { "Quantity must be positive" }
  }
}
