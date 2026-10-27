package com.hyperscale.commerce.orderquery.api;

import com.hyperscale.commerce.orderquery.domain.OrderNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice(basePackages = {"com.hyperscale.commerce.orderquery"})
public class OrderErrorHandler {
  private static final Logger logger = LoggerFactory.getLogger(OrderErrorHandler.class);

  @ExceptionHandler(OrderNotFoundException.class)
  public ResponseEntity<OrderError> handleNotFound(OrderNotFoundException ex) {
    logger.info("Order not found: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new OrderError(ex.getMessage() != null ? ex.getMessage() : "Order not found"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<OrderError> handleBadRequest(IllegalArgumentException ex) {
    logger.debug("Bad request: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new OrderError(ex.getMessage() != null ? ex.getMessage() : "Bad request"));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<OrderError> handleValidation(MethodArgumentNotValidException ex) {
    logger.debug("Validation failed: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new OrderError("Validation failed"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<OrderError> handleInternal(Exception ex) {
    logger.error("Unexpected error", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new OrderError("Internal server error"));
  }
}
