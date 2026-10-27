package com.hyperscale.commerce.modules.order.api;

import com.hyperscale.commerce.modules.order.application.IdempotencyConflictException;
import com.hyperscale.commerce.modules.order.application.IdempotencyPayloadMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice(basePackages = "com.hyperscale.commerce.modules.order")
public class OrderErrorHandler {
  private final Logger logger = LoggerFactory.getLogger(OrderErrorHandler.class);

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<OrderError> handleBadRequest(IllegalArgumentException ex) {
    logger.debug("Bad request: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new OrderError(ex.getMessage() != null ? ex.getMessage() : "Bad request"));
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  public ResponseEntity<OrderError> handleIdempotencyConflict(IdempotencyConflictException ex) {
    logger.debug("Idempotency conflict: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new OrderError(ex.getMessage() != null ? ex.getMessage() : "Idempotency conflict"));
  }

  @ExceptionHandler(IdempotencyPayloadMismatchException.class)
  public ResponseEntity<OrderError> handleIdempotencyMismatch(
      IdempotencyPayloadMismatchException ex) {
    logger.debug("Idempotency payload mismatch: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
        .body(
            new OrderError(
                ex.getMessage() != null ? ex.getMessage() : "Idempotency key payload mismatch"));
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
