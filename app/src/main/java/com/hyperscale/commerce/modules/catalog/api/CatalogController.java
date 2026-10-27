package com.hyperscale.commerce.modules.catalog.api;

import com.hyperscale.commerce.modules.catalog.application.CatalogService;
import com.hyperscale.commerce.modules.catalog.application.PagedProductsDto;
import com.hyperscale.commerce.modules.catalog.application.ProductDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/catalog/products")
@Tag(name = "Catalog", description = "Product catalog operations")
public class CatalogController {
  private final CatalogService catalogService;

  public CatalogController(CatalogService catalogService) {
    this.catalogService = catalogService;
  }

  @Operation(
      summary = "List catalog products",
      description = "List products with optional search and pagination")
  @GetMapping
  public ResponseEntity<PagedProductsDto> listProducts(
      @RequestParam(required = false) String query,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    PagedProductsDto result = catalogService.listProducts(query, page, size);
    return ResponseEntity.ok(result);
  }

  @Operation(summary = "Get a product by id")
  @GetMapping("/{id}")
  public ResponseEntity<ProductDto> getProductById(@PathVariable long id) {
    ProductDto product = catalogService.getProductById(id);
    return ResponseEntity.ok(product);
  }

  @Operation(summary = "Get a product by SKU")
  @GetMapping("/sku/{sku}")
  public ResponseEntity<ProductDto> getProductBySku(@PathVariable String sku) {
    ProductDto product = catalogService.getProductBySku(sku);
    return ResponseEntity.ok(product);
  }

  @Operation(summary = "Get product availability by id")
  @GetMapping("/{id}/availability")
  public ResponseEntity<Map<String, String>> getProductAvailability(@PathVariable long id) {
    ProductDto product = catalogService.getProductById(id);
    return ResponseEntity.ok(Map.of("availability", product.availability()));
  }
}
