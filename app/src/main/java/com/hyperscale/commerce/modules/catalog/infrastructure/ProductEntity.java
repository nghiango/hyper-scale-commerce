package com.hyperscale.commerce.modules.catalog.infrastructure;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("catalog.products")
public record ProductEntity(
    @Id long id, String sku, String name, String description, int price, String availability) {}
