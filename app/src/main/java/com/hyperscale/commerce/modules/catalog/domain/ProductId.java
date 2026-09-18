package com.hyperscale.commerce.modules.catalog.domain;

public record ProductId(long value) {
  public ProductId {
    if (value <= 0) {
      throw new IllegalArgumentException("Product ID must be positive");
    }
  }
}
