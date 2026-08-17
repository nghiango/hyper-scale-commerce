package com.hyperscale.commerce.modules.order.api

import com.hyperscale.commerce.modules.order.application.IdempotencyDecision
import com.hyperscale.commerce.modules.order.application.IdempotencyService
import com.hyperscale.commerce.modules.order.application.OrderDto
import com.hyperscale.commerce.modules.order.application.OrderService
import com.hyperscale.commerce.modules.order.domain.OrderItem
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper

@RestController
@RequestMapping("/orders")
@Tag(name = "Order", description = "Order operations")
class OrderController(
    private val orderService: OrderService,
    private val idempotencyService: IdempotencyService,
    private val objectMapper: ObjectMapper,
) {

  @Operation(
      summary = "Create an order", description = "Creates an order and publishes OrderPlaced")
  @PostMapping
  fun createOrder(
      @Valid @RequestBody request: CreateOrderRequest,
      @RequestHeader(value = "Idempotency-Key", required = false) idempotencyKey: String?,
  ): ResponseEntity<Any> {
    if (idempotencyKey.isNullOrBlank()) {
      val order = orderService.createOrder(request.items.map { OrderItem(it.sku, it.quantity) })
      return ResponseEntity.status(HttpStatus.CREATED).body(order)
    }

    return executeWithIdempotency(idempotencyKey, request)
  }

  private fun executeWithIdempotency(
      key: String,
      request: CreateOrderRequest
  ): ResponseEntity<Any> {
    val requestJson = objectMapper.writeValueAsString(request)
    val requestHash = idempotencyService.computeHash(requestJson)

    val decision = idempotencyService.evaluate(key, requestHash)
    if (decision is IdempotencyDecision.Replay) {
      val cachedDto = objectMapper.readValue(decision.body, OrderDto::class.java)
      return ResponseEntity.status(decision.statusCode).body(cachedDto)
    }

    val order =
        runCatching {
              orderService.createOrder(request.items.map { OrderItem(it.sku, it.quantity) })
            }
            .onFailure { idempotencyService.fail(key) }
            .getOrThrow()

    val responseJson = objectMapper.writeValueAsString(order)
    idempotencyService.complete(key, HttpStatus.CREATED.value(), responseJson)
    return ResponseEntity.status(HttpStatus.CREATED).body(order)
  }
}
