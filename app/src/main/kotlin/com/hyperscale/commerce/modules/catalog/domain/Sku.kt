package com.hyperscale.commerce.modules.catalog.domain

data class Sku(val value: String) {
  init {
    require(value.isNotBlank()) { "SKU must not be blank" }
    require(value.length <= MAX_LENGTH) { "SKU must not exceed $MAX_LENGTH characters" }
  }

  companion object {
    private const val MAX_LENGTH = 255
  }
}
