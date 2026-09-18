package com.hyperscale.commerce.modules.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyperscale.commerce.modules.catalog.domain.Availability;
import com.hyperscale.commerce.modules.catalog.domain.Money;
import com.hyperscale.commerce.modules.catalog.domain.Product;
import com.hyperscale.commerce.modules.catalog.domain.ProductId;
import com.hyperscale.commerce.modules.catalog.domain.ProductNotFoundException;
import com.hyperscale.commerce.modules.catalog.domain.ProductRepository;
import com.hyperscale.commerce.modules.catalog.domain.Sku;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CatalogServiceTest {

  @Test
  @DisplayName("lists products with pagination")
  void listsProductsWithPagination() {
    FakeProductRepository fake = new FakeProductRepository();
    fake.add(product(1L));
    CatalogService service = new CatalogService(fake, null, null, null);

    PagedProductsDto result = service.listProducts(null, 0, 10);

    assertThat(result.total()).isEqualTo(1);
    assertThat(result.items()).hasSize(1);
    assertThat(result.items().get(0).id()).isEqualTo(1);
  }

  @Test
  @DisplayName("rejects negative page")
  void rejectsNegativePage() {
    CatalogService service = new CatalogService(new FakeProductRepository(), null, null, null);

    assertThatThrownBy(() -> service.listProducts(null, -1, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("rejects invalid size")
  void rejectsInvalidSize() {
    CatalogService service = new CatalogService(new FakeProductRepository(), null, null, null);

    assertThatThrownBy(() -> service.listProducts(null, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("finds product by id")
  void findsProductById() {
    FakeProductRepository fake = new FakeProductRepository();
    fake.add(product(1L));
    CatalogService service = new CatalogService(fake, null, null, null);

    ProductDto result = service.getProductById(1L);

    assertThat(result.sku()).isEqualTo("SKU-1");
  }

  @Test
  @DisplayName("throws when product not found by id")
  void throwsWhenProductNotFoundById() {
    CatalogService service = new CatalogService(new FakeProductRepository(), null, null, null);

    assertThatThrownBy(() -> service.getProductById(99L))
        .isInstanceOf(ProductNotFoundException.class);
  }

  @Test
  @DisplayName("finds product by sku")
  void findsProductBySku() {
    FakeProductRepository fake = new FakeProductRepository();
    fake.add(product(2L));
    CatalogService service = new CatalogService(fake, null, null, null);

    ProductDto result = service.getProductBySku("SKU-2");

    assertThat(result.id()).isEqualTo(2);
  }

  @Test
  @DisplayName("throws when product not found by sku")
  void throwsWhenProductNotFoundBySku() {
    CatalogService service = new CatalogService(new FakeProductRepository(), null, null, null);

    assertThatThrownBy(() -> service.getProductBySku("MISSING"))
        .isInstanceOf(ProductNotFoundException.class);
  }

  private static Product product(long id) {
    return new Product(
        new ProductId(id),
        new Sku("SKU-" + id),
        "Product " + id,
        null,
        new Money(10000),
        Availability.IN_STOCK);
  }

  private static class FakeProductRepository implements ProductRepository {
    private final List<Product> products = new ArrayList<>();

    void add(Product product) {
      products.add(product);
    }

    @Override
    public Product findById(ProductId id) {
      return products.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }

    @Override
    public Product findBySku(Sku sku) {
      return products.stream().filter(p -> p.sku().equals(sku)).findFirst().orElse(null);
    }

    @Override
    public List<Product> search(String query, int page, int size) {
      return products.stream()
          .filter(p -> query == null || p.name().contains(query) || p.sku().value().contains(query))
          .skip((long) page * size)
          .limit(size)
          .toList();
    }

    @Override
    public long count(String query) {
      return products.stream()
          .filter(p -> query == null || p.name().contains(query) || p.sku().value().contains(query))
          .count();
    }
  }
}
