package com.hyperscale.commerce.modules.catalog.api

import com.hyperscale.commerce.modules.catalog.domain.ProductNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

@ControllerAdvice(basePackages = ["com.hyperscale.commerce.modules.catalog"])
class CatalogErrorHandler {
  private val logger = LoggerFactory.getLogger(CatalogErrorHandler::class.java)

  @ExceptionHandler(ProductNotFoundException::class)
  fun handleNotFound(ex: ProductNotFoundException): ResponseEntity<ApiError> {
    logger.info("Product not found: {}", ex.message)
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiError(ex.message ?: "Product not found"))
  }

  @ExceptionHandler(IllegalArgumentException::class)
  fun handleBadRequest(ex: IllegalArgumentException): ResponseEntity<ApiError> {
    logger.debug("Bad request: {}", ex.message)
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError(ex.message ?: "Bad request"))
  }

  @ExceptionHandler(Exception::class)
  fun handleInternal(ex: Exception): ResponseEntity<ApiError> {
    logger.error("Unexpected error", ex)
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiError("Internal server error"))
  }
}
