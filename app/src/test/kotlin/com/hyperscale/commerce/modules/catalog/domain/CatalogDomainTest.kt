package com.hyperscale.commerce.modules.catalog.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CatalogDomainTest {

  @Test
  fun `ProductId accepts a positive value`() {
    val id = ProductId(1)
    assertThat(id.value).isEqualTo(1)
  }

  @ParameterizedTest
  @ValueSource(longs = [0, -1])
  fun `ProductId rejects non-positive values`(value: Long) {
    assertThatThrownBy { ProductId(value) }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("Product ID must be positive")
  }

  @Test
  fun `Sku accepts a non-blank value`() {
    val sku = Sku("PROD-001")
    assertThat(sku.value).isEqualTo("PROD-001")
  }

  @ParameterizedTest
  @ValueSource(strings = ["", "   "])
  fun `Sku rejects blank values`(value: String) {
    assertThatThrownBy { Sku(value) }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("SKU must not be blank")
  }

  @Test
  fun `Sku rejects a value that exceeds the maximum length`() {
    assertThatThrownBy { Sku("a".repeat(256)) }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("SKU must not exceed")
  }

  @Test
  fun `Money accepts a non-negative value`() {
    val money = Money(12345)
    assertThat(money.amount).isEqualTo(12345)
  }

  @Test
  fun `Money rejects a negative value`() {
    assertThatThrownBy { Money(-1) }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("must not be negative")
  }

  @Test
  fun `Product can be created with valid fields`() {
    val product =
        Product(
            id = ProductId(1),
            sku = Sku("TEST-123"),
            name = "Test Product",
            description = "A test product",
            price = Money(9999),
            availability = Availability.IN_STOCK,
        )

    assertThat(product.sku.value).isEqualTo("TEST-123")
    assertThat(product.price.amount).isEqualTo(9999)
    assertThat(product.availability).isEqualTo(Availability.IN_STOCK)
  }

  @ParameterizedTest
  @ValueSource(strings = ["", "   "])
  fun `Product rejects invalid names`(name: String) {
    assertThatThrownBy {
          Product(
              id = ProductId(1),
              sku = Sku("TEST-123"),
              name = name,
              description = null,
              price = Money(9999),
              availability = Availability.IN_STOCK,
          )
        }
        .isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun `Product rejects a name that exceeds the maximum length`() {
    assertThatThrownBy {
          Product(
              id = ProductId(1),
              sku = Sku("TEST-123"),
              name = "a".repeat(256),
              description = null,
              price = Money(9999),
              availability = Availability.IN_STOCK,
          )
        }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("Product name must not exceed")
  }
}
