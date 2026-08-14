package com.hyperscale.commerce.modules.order.api

import com.hyperscale.commerce.modules.order.application.OrderDto
import com.hyperscale.commerce.modules.order.application.OrderQueryService
import com.hyperscale.commerce.modules.order.application.OrderService
import com.hyperscale.commerce.modules.order.application.PagedOrdersDto
import com.hyperscale.commerce.modules.order.domain.OrderItem
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/orders")
@Tag(name = "Order", description = "Order operations")
class OrderController(
    private val orderService: OrderService,
    private val orderQueryService: OrderQueryService,
) {

  @Operation(
      summary = "Create an order", description = "Creates an order and publishes OrderPlaced")
  @PostMapping
  fun createOrder(@Valid @RequestBody request: CreateOrderRequest): ResponseEntity<OrderDto> {
    val order = orderService.createOrder(request.items.map { OrderItem(it.sku, it.quantity) })
    return ResponseEntity.status(HttpStatus.CREATED).body(order)
  }

  @Operation(
      summary = "List orders", description = "List orders from the read model with pagination")
  @GetMapping
  fun listOrders(
      @RequestParam(defaultValue = "0") page: Int,
      @RequestParam(defaultValue = "20") size: Int,
  ): ResponseEntity<PagedOrdersDto> {
    return ResponseEntity.ok(orderQueryService.listOrders(page, size))
  }

  @Operation(summary = "Get an order by id")
  @GetMapping("/{id}")
  fun getOrder(@PathVariable id: Long): ResponseEntity<OrderDto> {
    return ResponseEntity.ok(orderQueryService.getOrder(id))
  }
}
