package com.hyperscale.commerce.modules.catalog.api

import com.hyperscale.commerce.modules.catalog.application.CatalogService
import com.hyperscale.commerce.modules.catalog.application.PagedProductsDto
import com.hyperscale.commerce.modules.catalog.application.ProductDto
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/catalog/products")
@Tag(name = "Catalog", description = "Product catalog operations")
class CatalogController(private val catalogService: CatalogService) {

  @Operation(
      summary = "List catalog products",
      description = "List products with optional search and pagination")
  @GetMapping
  fun listProducts(
      @RequestParam(required = false) query: String?,
      @RequestParam(defaultValue = "0") page: Int,
      @RequestParam(defaultValue = "20") size: Int,
  ): ResponseEntity<PagedProductsDto> {
    val result = catalogService.listProducts(query, page, size)
    return ResponseEntity.ok(result)
  }

  @Operation(summary = "Get a product by id")
  @GetMapping("/{id}")
  fun getProductById(@PathVariable id: Long): ResponseEntity<ProductDto> {
    val product = catalogService.getProductById(id)
    return ResponseEntity.ok(product)
  }

  @Operation(summary = "Get a product by SKU")
  @GetMapping("/sku/{sku}")
  fun getProductBySku(@PathVariable sku: String): ResponseEntity<ProductDto> {
    val product = catalogService.getProductBySku(sku)
    return ResponseEntity.ok(product)
  }

  @Operation(summary = "Get product availability by id")
  @GetMapping("/{id}/availability")
  fun getProductAvailability(@PathVariable id: Long): ResponseEntity<Map<String, String>> {
    val product = catalogService.getProductById(id)
    return ResponseEntity.ok(mapOf("availability" to product.availability))
  }
}
