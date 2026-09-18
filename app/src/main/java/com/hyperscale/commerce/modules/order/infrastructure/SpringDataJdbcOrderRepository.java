package com.hyperscale.commerce.modules.order.infrastructure;

import com.hyperscale.commerce.modules.order.domain.Order;
import com.hyperscale.commerce.modules.order.domain.OrderItem;
import com.hyperscale.commerce.modules.order.domain.OrderRepository;
import com.hyperscale.commerce.modules.order.domain.OrderStatus;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

@Repository
public class SpringDataJdbcOrderRepository implements OrderRepository {
  private final OrderJdbcRepository orderJdbcRepository;

  public SpringDataJdbcOrderRepository(OrderJdbcRepository orderJdbcRepository) {
    this.orderJdbcRepository = orderJdbcRepository;
  }

  @Override
  public Order save(List<OrderItem> items) {
    OrderEntity entity =
        new OrderEntity(
            null,
            OrderStatus.PLACED.name(),
            null,
            items.stream()
                .map(i -> new OrderItemEntity(null, i.sku(), i.quantity()))
                .collect(Collectors.toList()));
    OrderEntity saved = orderJdbcRepository.save(entity);
    Long savedId = Objects.requireNonNull(saved.id(), "Order ID must be generated after save");
    OrderEntity reloaded = orderJdbcRepository.findById(savedId).orElseThrow();
    return toOrder(reloaded);
  }

  @Override
  public Order findById(long id) {
    return orderJdbcRepository.findById(id).map(this::toOrder).orElse(null);
  }

  @Override
  public boolean updateStatus(long id, OrderStatus status) {
    int rows = orderJdbcRepository.updateStatus(id, status.name());
    return rows > 0;
  }

  private Order toOrder(OrderEntity entity) {
    return new Order(
        Objects.requireNonNull(entity.id(), "Order entity ID must not be null"),
        OrderStatus.valueOf(entity.status()),
        entity.items().stream()
            .map(i -> new OrderItem(i.sku(), i.quantity()))
            .collect(Collectors.toList()),
        Objects.requireNonNull(entity.createdAt(), "Order entity createdAt must not be null"));
  }
}
