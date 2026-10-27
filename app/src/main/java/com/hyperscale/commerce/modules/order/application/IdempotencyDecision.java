package com.hyperscale.commerce.modules.order.application;

public sealed interface IdempotencyDecision
    permits IdempotencyDecision.Proceed, IdempotencyDecision.Replay {

  final class Proceed implements IdempotencyDecision {
    public static final Proceed INSTANCE = new Proceed();

    private Proceed() {}
  }

  record Replay(int statusCode, String body) implements IdempotencyDecision {}
}
