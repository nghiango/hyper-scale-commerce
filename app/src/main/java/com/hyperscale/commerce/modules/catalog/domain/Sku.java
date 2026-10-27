package com.hyperscale.commerce.modules.catalog.domain;

public record Sku(String value) {
  private static final int MAX_LENGTH = 255;

  public Sku {
    if (value.isBlank()) {
      throw new IllegalArgumentException("SKU must not be blank");
    }
    if (value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("SKU must not exceed " + MAX_LENGTH + " characters");
    }
  }
}
