package com.hyperscale.commerce.modules.catalog.application;

import java.util.List;

public record PagedProductsDto(long total, List<ProductDto> items) {}
