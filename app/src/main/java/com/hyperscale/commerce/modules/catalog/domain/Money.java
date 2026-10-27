package com.hyperscale.commerce.modules.catalog.domain;

public record Money(int amount) {
  private static final int MAX_AMOUNT = 1_000_000_000;

  public Money {
    if (amount < 0) {
      throw new IllegalArgumentException("Money amount must not be negative");
    }
    if (amount > MAX_AMOUNT) {
      throw new IllegalArgumentException("Money amount exceeds the allowed maximum");
    }
  }
}
