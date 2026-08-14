package com.hyperscale.commerce.orderquery.api

import com.hyperscale.commerce.orderquery.application.OrderDto
import com.hyperscale.commerce.orderquery.application.OrderQueryService
import com.hyperscale.commerce.orderquery.application.PagedOrdersDto
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/orders")
@Tag(name = "Order", description = "Order query operations")
class OrderQueryController(
    private val orderQueryService: OrderQueryService,
) {

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
