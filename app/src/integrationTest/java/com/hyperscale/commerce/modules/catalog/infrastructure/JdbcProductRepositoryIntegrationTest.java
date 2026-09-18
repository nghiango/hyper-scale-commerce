package com.hyperscale.commerce.modules.catalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyperscale.commerce.modules.catalog.domain.Product;
import com.hyperscale.commerce.modules.catalog.domain.ProductId;
import com.hyperscale.commerce.modules.catalog.domain.ProductRepository;
import com.hyperscale.commerce.modules.catalog.domain.Sku;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class JdbcProductRepositoryIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

  private final ProductRepository repository;
  private final JdbcTemplate jdbcTemplate;

  @Autowired
  public JdbcProductRepositoryIntegrationTest(
      ProductRepository repository, JdbcTemplate jdbcTemplate) {
    this.repository = repository;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Test
  @DisplayName("finds product by id")
  void findsProductById() {
    long id = seedProduct("FIND-BY-ID", "Find By ID");

    Product product = repository.findById(new ProductId(id));

    assertThat(product).isNotNull();
    assertThat(product.sku().value()).isEqualTo("FIND-BY-ID");
    assertThat(product.name()).isEqualTo("Find By ID");
  }

  @Test
  @DisplayName("finds product by sku")
  void findsProductBySku() {
    seedProduct("FIND-BY-SKU", "Find By SKU");

    Product product = repository.findBySku(new Sku("FIND-BY-SKU"));

    assertThat(product).isNotNull();
    assertThat(product.name()).isEqualTo("Find By SKU");
  }

  @Test
  @DisplayName("returns null for unknown product")
  void returnsNullForUnknownProduct() {
    Product product = repository.findById(new ProductId(Long.MAX_VALUE));

    assertThat(product).isNull();
  }

  @Test
  @DisplayName("searches products by name")
  void searchesProductsByName() {
    seedProduct("SEARCH-1", "Unique Searchable Product");
    seedProduct("SEARCH-2", "Another Product");

    List<Product> result = repository.search("Searchable", 0, 10);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).name()).isEqualTo("Unique Searchable Product");
  }

  @Test
  @DisplayName("searches products by sku")
  void searchesProductsBySku() {
    seedProduct("REPOSITORY-UNIQUE-SKU-123", "By SKU");

    List<Product> result = repository.search("REPOSITORY-UNIQUE-SKU", 0, 10);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).sku().value()).isEqualTo("REPOSITORY-UNIQUE-SKU-123");
  }

  @Test
  @DisplayName("paginates search results")
  void paginatesSearchResults() {
    seedProduct("PAGE-1", "Page One");
    seedProduct("PAGE-2", "Page Two");

    List<Product> firstPage = repository.search("Page", 0, 1);
    List<Product> secondPage = repository.search("Page", 1, 1);

    assertThat(firstPage).hasSize(1);
    assertThat(secondPage).hasSize(1);
    assertThat(firstPage.get(0).sku().value()).isNotEqualTo(secondPage.get(0).sku().value());
  }

  @Test
  @DisplayName("counts products matching query")
  void countsProductsMatchingQuery() {
    seedProduct("COUNT-1", "Count Product Alpha");
    seedProduct("COUNT-2", "Count Product Beta");
    seedProduct("COUNT-3", "Something Else");

    long count = repository.count("Count Product");

    assertThat(count).isEqualTo(2);
  }

  private long seedProduct(String sku, String name) {
    return Objects.requireNonNull(
        jdbcTemplate.queryForObject(
            """
            INSERT INTO catalog.products (sku, name, description, price, availability, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, now(), now())
            ON CONFLICT (sku) DO UPDATE SET
              name = EXCLUDED.name,
              description = EXCLUDED.description,
              price = EXCLUDED.price,
              availability = EXCLUDED.availability,
              updated_at = now()
            RETURNING id
            """,
            Long.class,
            sku,
            name,
            "A test product",
            9999,
            "IN_STOCK"));
  }
}
