package com.hyperscale.commerce.modules.catalog.application;

import com.hyperscale.commerce.config.cache.CacheInvalidationService;
import com.hyperscale.commerce.config.cache.L2CacheStore;
import com.hyperscale.commerce.config.cache.NearCache;
import com.hyperscale.commerce.modules.catalog.domain.Product;
import com.hyperscale.commerce.modules.catalog.domain.ProductId;
import com.hyperscale.commerce.modules.catalog.domain.ProductNotFoundException;
import com.hyperscale.commerce.modules.catalog.domain.ProductRepository;
import com.hyperscale.commerce.modules.catalog.domain.Sku;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CatalogService {
  private static final int MIN_PAGE = 0;
  private static final int MIN_SIZE = 1;
  private static final int MAX_SIZE = 100;
  private static final long PRODUCT_CACHE_MAX_SIZE = 10_000L;
  private static final long PRODUCT_CACHE_TTL_SECONDS = 60L;
  private static final long LIST_CACHE_MAX_SIZE = 2_000L;
  private static final long LIST_CACHE_TTL_SECONDS = 30L;
  private static final String PRODUCT_CACHE_NAME = "catalog_products";
  private static final String LIST_CACHE_NAME = "catalog_list";

  private final ProductRepository productRepository;
  private final CacheInvalidationService invalidationService;
  private final NearCache<Long, ProductDto> productCache;
  private final NearCache<String, PagedProductsDto> listCache;

  public CatalogService(
      ProductRepository productRepository,
      @Autowired(required = false) @Nullable MeterRegistry meterRegistry,
      @Autowired(required = false) @Nullable L2CacheStore l2Store,
      @Autowired(required = false) @Nullable CacheInvalidationService invalidationService) {
    this.productRepository = productRepository;
    this.invalidationService = invalidationService;
    this.productCache =
        new NearCache<>(
            PRODUCT_CACHE_NAME,
            PRODUCT_CACHE_MAX_SIZE,
            Duration.ofSeconds(PRODUCT_CACHE_TTL_SECONDS),
            Duration.ofMinutes(10),
            meterRegistry,
            JsonMapper.builder().findAndAddModules().build(),
            l2Store,
            ProductDto.class);
    this.listCache =
        new NearCache<>(
            LIST_CACHE_NAME,
            LIST_CACHE_MAX_SIZE,
            Duration.ofSeconds(LIST_CACHE_TTL_SECONDS),
            Duration.ofMinutes(10),
            meterRegistry,
            JsonMapper.builder().findAndAddModules().build(),
            l2Store,
            PagedProductsDto.class);
    if (invalidationService != null) {
      invalidationService.registerCache(productCache);
      invalidationService.registerCache(listCache);
    }
  }

  @Transactional(readOnly = true)
  public PagedProductsDto listProducts(String query, int page, int size) {
    if (page < MIN_PAGE) {
      throw new IllegalArgumentException("Page must not be negative");
    }
    if (size < MIN_SIZE || size > MAX_SIZE) {
      throw new IllegalArgumentException("Size must be between " + MIN_SIZE + " and " + MAX_SIZE);
    }

    String cacheKey = (query != null ? query : "") + ":" + page + ":" + size;
    PagedProductsDto result =
        listCache.get(
            cacheKey,
            () -> {
              long total = productRepository.count(query);
              List<Product> products = productRepository.search(query, page, size);
              return new PagedProductsDto(total, products.stream().map(this::toDto).toList());
            });
    if (result == null) {
      throw new IllegalStateException("Failed to compute product list");
    }
    return result;
  }

  @Transactional(readOnly = true)
  public ProductDto getProductById(long id) {
    ProductDto cached =
        productCache.get(
            id,
            () -> {
              Product product = productRepository.findById(new ProductId(id));
              return product != null ? toDto(product) : null;
            });

    if (cached == null) {
      throw new ProductNotFoundException("Product with id " + id + " not found");
    }
    return cached;
  }

  @Transactional(readOnly = true)
  public ProductDto getProductBySku(String sku) {
    Product product = productRepository.findBySku(new Sku(sku));
    if (product == null) {
      throw new ProductNotFoundException("Product with SKU " + sku + " not found");
    }
    return toDto(product);
  }

  public void evictAll() {
    productCache.evictAll();
    listCache.evictAll();
    if (invalidationService != null) {
      invalidationService.publishInvalidation(PRODUCT_CACHE_NAME, null);
      invalidationService.publishInvalidation(LIST_CACHE_NAME, null);
    }
  }

  public void evictProduct(long id) {
    productCache.evict(id);
    listCache.evictAll();
    if (invalidationService != null) {
      invalidationService.publishInvalidation(PRODUCT_CACHE_NAME, Long.toString(id));
      invalidationService.publishInvalidation(LIST_CACHE_NAME, null);
    }
  }

  private ProductDto toDto(Product product) {
    return new ProductDto(
        product.id().value(),
        product.sku().value(),
        product.name(),
        product.description(),
        product.price().amount(),
        product.availability().name());
  }
}
