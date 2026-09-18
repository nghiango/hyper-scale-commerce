package com.hyperscale.commerce.modules.catalog.application;

public record ProductDto(
    long id, String sku, String name, String description, int price, String availability) {}
