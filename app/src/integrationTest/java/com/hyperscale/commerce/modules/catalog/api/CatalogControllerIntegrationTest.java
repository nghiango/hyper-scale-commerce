package com.hyperscale.commerce.modules.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class CatalogControllerIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

  private final int port;
  private final JdbcTemplate jdbcTemplate;
  private final HttpClient client = HttpClient.newHttpClient();

  @Autowired
  public CatalogControllerIntegrationTest(@LocalServerPort int port, JdbcTemplate jdbcTemplate) {
    this.port = port;
    this.jdbcTemplate = jdbcTemplate;
  }

  private String baseUrl() {
    return "http://localhost:" + port + "/catalog/products";
  }

  @Test
  @DisplayName("lists products with pagination")
  void listsProductsWithPagination() throws Exception {
    seedProduct("LIST-1", "Listable Product");

    HttpResponse<String> response = get(baseUrl() + "?query=Listable&page=0&size=10");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("Listable Product");
  }

  @Test
  @DisplayName("lists products with optional search")
  void listsProductsWithOptionalSearch() throws Exception {
    seedProduct("SEARCH-1", "Searchable Product");
    seedProduct("SEARCH-2", "Other Product");

    HttpResponse<String> response = get(baseUrl() + "?query=Searchable");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("Searchable Product");
    assertThat(response.body()).doesNotContain("Other Product");
  }

  @Test
  @DisplayName("rejects invalid page parameter")
  void rejectsInvalidPageParameter() throws Exception {
    HttpResponse<String> response = get(baseUrl() + "?page=-1&size=10");

    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  @DisplayName("rejects invalid size parameter")
  void rejectsInvalidSizeParameter() throws Exception {
    HttpResponse<String> response = get(baseUrl() + "?page=0&size=0");

    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  @DisplayName("finds product by id")
  void findsProductById() throws Exception {
    long id = seedProduct("BY-ID-1", "By ID Product");

    HttpResponse<String> response = get(baseUrl() + "/" + id);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("By ID Product");
  }

  @Test
  @DisplayName("returns 404 for unknown product id")
  void returns404ForUnknownProductId() throws Exception {
    HttpResponse<String> response = get(baseUrl() + "/" + Long.MAX_VALUE);

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  @DisplayName("finds product by sku")
  void findsProductBySku() throws Exception {
    seedProduct("UNIQUE-SKU", "By SKU Product");

    HttpResponse<String> response = get(baseUrl() + "/sku/UNIQUE-SKU");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("By SKU Product");
  }

  @Test
  @DisplayName("returns 404 for unknown sku")
  void returns404ForUnknownSku() throws Exception {
    HttpResponse<String> response = get(baseUrl() + "/sku/UNKNOWN");

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  @DisplayName("returns product availability")
  void returnsProductAvailability() throws Exception {
    long id = seedProduct("AVAIL-1", "Availability Product");

    HttpResponse<String> response = get(baseUrl() + "/" + id + "/availability");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("IN_STOCK");
  }

  @Test
  @DisplayName("returns 404 availability for unknown product")
  void returns404AvailabilityForUnknownProduct() throws Exception {
    HttpResponse<String> response = get(baseUrl() + "/" + Long.MAX_VALUE + "/availability");

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  @DisplayName("openapi document contains catalog paths")
  void openapiDocumentContainsCatalogPaths() throws Exception {
    HttpResponse<String> response = get("http://localhost:" + port + "/v3/api-docs");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("/catalog/products");
  }

  private HttpResponse<String> get(String url) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
    return client.send(request, BodyHandlers.ofString());
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
