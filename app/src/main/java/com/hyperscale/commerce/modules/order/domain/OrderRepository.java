package com.hyperscale.commerce.modules.order.domain;

import java.util.List;

public interface OrderRepository {

  Order save(List<OrderItem> items);

  Order findById(long id);

  boolean updateStatus(long id, OrderStatus status);
}
