package com.hyperscale.commerce.modules.order.api;

import com.hyperscale.commerce.modules.order.application.IdempotencyDecision;
import com.hyperscale.commerce.modules.order.application.IdempotencyService;
import com.hyperscale.commerce.modules.order.application.OrderDto;
import com.hyperscale.commerce.modules.order.application.OrderService;
import com.hyperscale.commerce.modules.order.domain.OrderItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/orders")
@Tag(name = "Order", description = "Order operations")
public class OrderController {
  private final OrderService orderService;
  private final IdempotencyService idempotencyService;
  private final ObjectMapper objectMapper;

  public OrderController(
      OrderService orderService, IdempotencyService idempotencyService, ObjectMapper objectMapper) {
    this.orderService = orderService;
    this.idempotencyService = idempotencyService;
    this.objectMapper = objectMapper;
  }

  @Operation(
      summary = "Create an order",
      description = "Creates an order and publishes OrderPlaced")
  @PostMapping
  public ResponseEntity<Object> createOrder(
      @Valid @RequestBody CreateOrderRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      OrderDto order =
          orderService.createOrder(
              request.items().stream()
                  .map(i -> new OrderItem(i.sku(), i.quantity()))
                  .collect(Collectors.toList()));
      return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    return executeWithIdempotency(idempotencyKey, request);
  }

  private ResponseEntity<Object> executeWithIdempotency(String key, CreateOrderRequest request) {
    String requestJson = objectMapper.writeValueAsString(request);
    String requestHash = idempotencyService.computeHash(requestJson);

    IdempotencyDecision decision = idempotencyService.evaluate(key, requestHash);
    if (decision instanceof IdempotencyDecision.Replay replay) {
      OrderDto cachedDto = objectMapper.readValue(replay.body(), OrderDto.class);
      return ResponseEntity.status(replay.statusCode()).body(cachedDto);
    }

    OrderDto order;
    try {
      order =
          orderService.createOrder(
              request.items().stream()
                  .map(i -> new OrderItem(i.sku(), i.quantity()))
                  .collect(Collectors.toList()));
    } catch (RuntimeException ex) {
      idempotencyService.fail(key);
      throw ex;
    }

    String responseJson = objectMapper.writeValueAsString(order);
    idempotencyService.complete(key, HttpStatus.CREATED.value(), responseJson);
    return ResponseEntity.status(HttpStatus.CREATED).body(order);
  }
}
