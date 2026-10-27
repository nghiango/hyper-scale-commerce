package com.hyperscale.commerce.modules.catalog.domain;

import java.util.List;

public interface ProductRepository {
  Product findById(ProductId id);

  Product findBySku(Sku sku);

  List<Product> search(String query, int page, int size);

  long count(String query);
}
