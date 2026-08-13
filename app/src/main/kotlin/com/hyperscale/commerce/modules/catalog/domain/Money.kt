package com.hyperscale.commerce.modules.catalog.domain

data class Money(val amount: Int) {
  init {
    require(amount >= 0) { "Money amount must not be negative" }
    require(amount <= MAX_AMOUNT) { "Money amount exceeds the allowed maximum" }
  }

  companion object {
    private const val MAX_AMOUNT = 1_000_000_000
  }
}
