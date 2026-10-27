package com.hyperscale.commerce.modules.catalog.domain;

public record Product(
    ProductId id,
    Sku sku,
    String name,
    String description,
    Money price,
    Availability availability) {
  private static final int MAX_NAME_LENGTH = 255;

  public Product {
    if (name.isBlank()) {
      throw new IllegalArgumentException("Product name must not be blank");
    }
    if (name.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "Product name must not exceed " + MAX_NAME_LENGTH + " characters");
    }
  }
}
