package com.hyperscale.commerce.modules.order.domain;

public record OrderItem(String sku, int quantity) {
  public OrderItem {
    if (sku.isBlank()) {
      throw new IllegalArgumentException("SKU must not be blank");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Quantity must be positive");
    }
  }
}
