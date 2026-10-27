package com.hyperscale.commerce.modules.order.application;

public class IdempotencyPayloadMismatchException extends RuntimeException {
  public IdempotencyPayloadMismatchException(String message) {
    super(message);
  }
}
