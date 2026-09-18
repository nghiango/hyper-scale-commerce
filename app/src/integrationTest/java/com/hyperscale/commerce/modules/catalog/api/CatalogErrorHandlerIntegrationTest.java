package com.hyperscale.commerce.modules.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class CatalogErrorHandlerIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

  private final int port;
  private final HttpClient client = HttpClient.newHttpClient();

  @Autowired
  public CatalogErrorHandlerIntegrationTest(@LocalServerPort int port) {
    this.port = port;
  }

  private String baseUrl() {
    return "http://localhost:" + port + "/catalog/products";
  }

  @Test
  @DisplayName("returns 404 with error body for unknown product")
  void returns404WithErrorBodyForUnknownProduct() throws Exception {
    HttpResponse<String> response = get(baseUrl() + "/99999");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).contains("error");
  }

  @Test
  @DisplayName("returns 400 with error body for invalid page")
  void returns400WithErrorBodyForInvalidPage() throws Exception {
    HttpResponse<String> response = get(baseUrl() + "?page=-1&size=10");

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("error");
  }

  @Test
  @DisplayName("returns 500 with error body for unexpected errors")
  void returns500WithErrorBodyForUnexpectedErrors() throws Exception {
    HttpResponse<String> response = get("http://localhost:" + port + "/catalog/test/error");

    assertThat(response.statusCode()).isEqualTo(500);
    assertThat(response.body()).contains("error");
    assertThat(response.body()).contains("Internal server error");
  }

  private HttpResponse<String> get(String url) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
    return client.send(request, BodyHandlers.ofString());
  }
}
