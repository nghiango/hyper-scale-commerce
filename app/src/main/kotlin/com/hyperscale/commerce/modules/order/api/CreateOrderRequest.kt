package com.hyperscale.commerce.modules.order.api

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive

data class CreateOrderRequest(
    @field:Valid @field:NotEmpty val items: List<CreateOrderItemRequest>,
)

data class CreateOrderItemRequest(
    @field:NotBlank val sku: String,
    @field:Positive val quantity: Int,
)
