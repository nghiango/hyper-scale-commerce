package com.hyperscale.commerce.modules.catalog.domain;

public class ProductNotFoundException extends RuntimeException {
  public ProductNotFoundException(String message) {
    super(message);
  }
}
