package com.hyperscale.commerce.modules.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogDomainTest {

  @Test
  @DisplayName("ProductId accepts a positive value")
  void productIdAcceptsAPositiveValue() {
    ProductId id = new ProductId(1);
    assertThat(id.value()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  @DisplayName("ProductId rejects non-positive values")
  void productIdRejectsNonPositiveValues(long value) {
    assertThatThrownBy(() -> new ProductId(value))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Product ID must be positive");
  }

  @Test
  @DisplayName("Sku accepts a non-blank value")
  void skuAcceptsANonBlankValue() {
    Sku sku = new Sku("PROD-001");
    assertThat(sku.value()).isEqualTo("PROD-001");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  @DisplayName("Sku rejects blank values")
  void skuRejectsBlankValues(String value) {
    assertThatThrownBy(() -> new Sku(value))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SKU must not be blank");
  }

  @Test
  @DisplayName("Sku rejects a value that exceeds the maximum length")
  void skuRejectsAValueThatExceedsTheMaximumLength() {
    assertThatThrownBy(() -> new Sku("a".repeat(256)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SKU must not exceed");
  }

  @Test
  @DisplayName("Money accepts a non-negative value")
  void moneyAcceptsANonNegativeValue() {
    Money money = new Money(12345);
    assertThat(money.amount()).isEqualTo(12345);
  }

  @Test
  @DisplayName("Money rejects a negative value")
  void moneyRejectsANegativeValue() {
    assertThatThrownBy(() -> new Money(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be negative");
  }

  @Test
  @DisplayName("Product can be created with valid fields")
  void productCanBeCreatedWithValidFields() {
    Product product =
        new Product(
            new ProductId(1),
            new Sku("TEST-123"),
            "Test Product",
            "A test product",
            new Money(9999),
            Availability.IN_STOCK);

    assertThat(product.sku().value()).isEqualTo("TEST-123");
    assertThat(product.price().amount()).isEqualTo(9999);
    assertThat(product.availability()).isEqualTo(Availability.IN_STOCK);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  @DisplayName("Product rejects invalid names")
  void productRejectsInvalidNames(String name) {
    assertThatThrownBy(
            () ->
                new Product(
                    new ProductId(1),
                    new Sku("TEST-123"),
                    name,
                    null,
                    new Money(9999),
                    Availability.IN_STOCK))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Product rejects a name that exceeds the maximum length")
  void productRejectsANameThatExceedsTheMaximumLength() {
    assertThatThrownBy(
            () ->
                new Product(
                    new ProductId(1),
                    new Sku("TEST-123"),
                    "a".repeat(256),
                    null,
                    new Money(9999),
                    Availability.IN_STOCK))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Product name must not exceed");
  }
}
