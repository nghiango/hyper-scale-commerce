package com.hyperscale.commerce.modules.order.application;

import com.hyperscale.commerce.config.observability.CorrelationIdFilter;
import com.hyperscale.commerce.contracts.OrderCancelledEvent;
import com.hyperscale.commerce.contracts.OrderPlacedEvent;
import com.hyperscale.commerce.contracts.OrderPlacedItem;
import com.hyperscale.commerce.modules.order.domain.Order;
import com.hyperscale.commerce.modules.order.domain.OrderItem;
import com.hyperscale.commerce.modules.order.domain.OrderRepository;
import com.hyperscale.commerce.modules.order.domain.OrderStatus;
import com.hyperscale.commerce.modules.shared.outbox.OutboxRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderService {
  private static final int EVENT_VERSION = 1;
  private static final long ORDER_PLACED_AGGREGATE_VERSION = 1L;
  private static final long ORDER_CANCELLED_AGGREGATE_VERSION = 2L;
  private static final String ORDER_PLACED_EVENT_TYPE = "OrderPlaced";
  private static final String ORDER_CANCELLED_EVENT_TYPE = "OrderCancelled";

  private final OrderRepository orderRepository;
  private final OutboxRepository outboxRepository;
  private final ObjectMapper objectMapper;
  private final Tracer tracer;
  private final Logger logger = LoggerFactory.getLogger(OrderService.class);

  public OrderService(
      OrderRepository orderRepository,
      OutboxRepository outboxRepository,
      ObjectMapper objectMapper,
      Tracer tracer) {
    this.orderRepository = orderRepository;
    this.outboxRepository = outboxRepository;
    this.objectMapper = objectMapper;
    this.tracer = tracer;
  }

  @Transactional
  public OrderDto createOrder(List<OrderItem> items) {
    Order order = orderRepository.save(items);
    logger.info(
        "OrderService creating order with mdcCorrelationId={} tracerCurrentTraceId={}",
        MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY),
        traceId(tracer.currentSpan()));
    Span span = tracer.currentSpan();
    String payload =
        objectMapper.writeValueAsString(
            new OrderPlacedEvent(
                EVENT_VERSION,
                UUID.randomUUID().toString(),
                order.id(),
                order.status().name(),
                order.createdAt(),
                order.items().stream()
                    .map(i -> new OrderPlacedItem(i.sku(), i.quantity()))
                    .collect(Collectors.toList()),
                ORDER_PLACED_AGGREGATE_VERSION,
                MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY),
                traceId(span),
                spanId(span),
                sampled(span)));
    outboxRepository.insert(Long.toString(order.id()), ORDER_PLACED_EVENT_TYPE, payload);
    return toDto(order);
  }

  @Transactional
  public boolean cancelOrder(long orderId, String reason) {
    Order existing = orderRepository.findById(orderId);
    if (existing == null) return false;
    if (existing.status() != OrderStatus.CANCELLED
        && orderRepository.updateStatus(orderId, OrderStatus.CANCELLED)) {
      emitOrderCancelled(orderId, reason);
    }
    return true;
  }

  private void emitOrderCancelled(long orderId, String reason) {
    Span span = tracer.currentSpan();
    String payload =
        objectMapper.writeValueAsString(
            new OrderCancelledEvent(
                EVENT_VERSION,
                UUID.randomUUID().toString(),
                orderId,
                reason,
                Instant.now(),
                ORDER_CANCELLED_AGGREGATE_VERSION,
                MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY),
                traceId(span),
                spanId(span),
                sampled(span)));
    outboxRepository.insert(Long.toString(orderId), ORDER_CANCELLED_EVENT_TYPE, payload);
  }

  private OrderDto toDto(Order order) {
    return new OrderDto(
        order.id(),
        order.status().name(),
        order.items().stream()
            .map(i -> new OrderItemDto(i.sku(), i.quantity()))
            .collect(Collectors.toList()),
        order.createdAt());
  }

  private static String traceId(Span span) {
    return span != null && span.context() != null ? span.context().traceId() : null;
  }

  private static String spanId(Span span) {
    return span != null && span.context() != null ? span.context().spanId() : null;
  }

  private static Boolean sampled(Span span) {
    return span != null && span.context() != null ? span.context().sampled() : null;
  }
}
