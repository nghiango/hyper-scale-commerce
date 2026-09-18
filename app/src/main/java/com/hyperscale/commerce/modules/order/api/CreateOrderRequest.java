package com.hyperscale.commerce.modules.order.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record CreateOrderRequest(@Valid @NotEmpty List<CreateOrderItemRequest> items) {}

record CreateOrderItemRequest(@NotBlank String sku, @Positive int quantity) {}
