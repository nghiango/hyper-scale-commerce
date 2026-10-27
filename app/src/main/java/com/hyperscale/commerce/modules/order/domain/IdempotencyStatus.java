package com.hyperscale.commerce.modules.order.domain;

public enum IdempotencyStatus {
  IN_PROGRESS,
  COMPLETED,
  FAILED,
}
