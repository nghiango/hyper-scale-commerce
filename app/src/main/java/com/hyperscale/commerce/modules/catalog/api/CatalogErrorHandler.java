package com.hyperscale.commerce.modules.catalog.api;

import com.hyperscale.commerce.modules.catalog.domain.ProductNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice(basePackages = "com.hyperscale.commerce.modules.catalog")
public class CatalogErrorHandler {
  private final Logger logger = LoggerFactory.getLogger(CatalogErrorHandler.class);

  @ExceptionHandler(ProductNotFoundException.class)
  public ResponseEntity<ApiError> handleNotFound(ProductNotFoundException ex) {
    logger.info("Product not found: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ApiError(ex.getMessage() != null ? ex.getMessage() : "Product not found"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException ex) {
    logger.debug("Bad request: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ApiError(ex.getMessage() != null ? ex.getMessage() : "Bad request"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleInternal(Exception ex) {
    logger.error("Unexpected error", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ApiError("Internal server error"));
  }
}
