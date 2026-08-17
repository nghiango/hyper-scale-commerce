package com.hyperscale.commerce.modules.order.api

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

@ControllerAdvice(basePackages = ["com.hyperscale.commerce.modules.order"])
class OrderErrorHandler {
  private val logger = LoggerFactory.getLogger(OrderErrorHandler::class.java)

  @ExceptionHandler(IllegalArgumentException::class)
  fun handleBadRequest(ex: IllegalArgumentException): ResponseEntity<OrderError> {
    logger.debug("Bad request: {}", ex.message)
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(OrderError(ex.message ?: "Bad request"))
  }

  @ExceptionHandler(
      com.hyperscale.commerce.modules.order.application.IdempotencyConflictException::class)
  fun handleIdempotencyConflict(
      ex: com.hyperscale.commerce.modules.order.application.IdempotencyConflictException
  ): ResponseEntity<OrderError> {
    logger.debug("Idempotency conflict: {}", ex.message)
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(OrderError(ex.message ?: "Idempotency conflict"))
  }

  @ExceptionHandler(
      com.hyperscale.commerce.modules.order.application.IdempotencyPayloadMismatchException::class)
  fun handleIdempotencyMismatch(
      ex: com.hyperscale.commerce.modules.order.application.IdempotencyPayloadMismatchException
  ): ResponseEntity<OrderError> {
    logger.debug("Idempotency payload mismatch: {}", ex.message)
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .body(OrderError(ex.message ?: "Idempotency key payload mismatch"))
  }

  @ExceptionHandler(MethodArgumentNotValidException::class)
  fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<OrderError> {
    logger.debug("Validation failed: {}", ex.message)
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(OrderError("Validation failed"))
  }

  @ExceptionHandler(Exception::class)
  fun handleInternal(ex: Exception): ResponseEntity<OrderError> {
    logger.error("Unexpected error", ex)
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(OrderError("Internal server error"))
  }
}
