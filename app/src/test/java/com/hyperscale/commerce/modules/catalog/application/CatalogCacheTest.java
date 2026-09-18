package com.hyperscale.commerce.modules.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyperscale.commerce.config.cache.CacheInvalidationService;
import com.hyperscale.commerce.modules.catalog.domain.Availability;
import com.hyperscale.commerce.modules.catalog.domain.Money;
import com.hyperscale.commerce.modules.catalog.domain.Product;
import com.hyperscale.commerce.modules.catalog.domain.ProductId;
import com.hyperscale.commerce.modules.catalog.domain.ProductRepository;
import com.hyperscale.commerce.modules.catalog.domain.Sku;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CatalogCacheTest {

  @Test
  @DisplayName("product eviction publishes product and list invalidations")
  void productEvictionPublishesProductAndListInvalidations() {
    ProductRepository repository = mock(ProductRepository.class);
    CacheInvalidationService invalidationService = mock(CacheInvalidationService.class);
    CatalogService service = new CatalogService(repository, null, null, invalidationService);
    clearInvocations(invalidationService);

    service.evictProduct(42L);

    verify(invalidationService).publishInvalidation("catalog_products", "42");
    verify(invalidationService).publishInvalidation("catalog_list", null);
  }

  @Test
  @DisplayName("repeated getProductById returns cached result without repository call")
  void repeatedGetProductByIdReturnsCachedResultWithoutRepositoryCall() {
    ProductRepository repository = mock(ProductRepository.class);
    CatalogService service = new CatalogService(repository, null, null, null);

    Product product =
        new Product(
            new ProductId(101L),
            new Sku("CACHE-SKU-1"),
            "Cached Product",
            "Description",
            new Money(4999),
            Availability.IN_STOCK);

    when(repository.findById(new ProductId(101L))).thenReturn(product);

    ProductDto res1 = service.getProductById(101L);
    ProductDto res2 = service.getProductById(101L);
    ProductDto res3 = service.getProductById(101L);

    assertThat(res1.name()).isEqualTo("Cached Product");
    assertThat(res2.name()).isEqualTo("Cached Product");
    assertThat(res3.name()).isEqualTo("Cached Product");

    // Repository should only be queried once due to Caffeine caching
    verify(repository, times(1)).findById(new ProductId(101L));
  }

  @Test
  @DisplayName("evictProduct clears cache and re-queries repository on subsequent get")
  void evictProductClearsCacheAndReQueriesRepositoryOnSubsequentGet() {
    ProductRepository repository = mock(ProductRepository.class);
    CatalogService service = new CatalogService(repository, null, null, null);

    Product product =
        new Product(
            new ProductId(202L),
            new Sku("CACHE-SKU-2"),
            "Evictable Product",
            "Description",
            new Money(1999),
            Availability.IN_STOCK);

    when(repository.findById(new ProductId(202L))).thenReturn(product);

    service.getProductById(202L);
    verify(repository, times(1)).findById(new ProductId(202L));

    service.evictProduct(202L);
    service.getProductById(202L);

    verify(repository, times(2)).findById(new ProductId(202L));
  }

  @Test
  @DisplayName(
      "singleflight stampede protection ensures concurrent callers execute loader only once")
  void singleflightStampedeProtectionEnsuresConcurrentCallersExecuteLoaderOnlyOnce()
      throws Exception {
    ProductRepository repository = mock(ProductRepository.class);
    AtomicInteger callCount = new AtomicInteger(0);

    when(repository.findById(new ProductId(303L)))
        .thenAnswer(
            invocation -> {
              Thread.sleep(50); // simulate database latency
              callCount.incrementAndGet();
              return new Product(
                  new ProductId(303L),
                  new Sku("STAMPEDE-SKU"),
                  "Stampede Product",
                  "Description",
                  new Money(9999),
                  Availability.IN_STOCK);
            });

    CatalogService service = new CatalogService(repository, null, null, null);
    int threadCount = 10;
    var executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);

    List<ProductDto> results = new ArrayList<>();

    for (int i = 0; i < threadCount; i++) {
      executor.submit(
          () -> {
            startLatch.await();
            ProductDto dto = service.getProductById(303L);
            synchronized (results) {
              results.add(dto);
            }
            doneLatch.countDown();
            return null;
          });
    }

    startLatch.countDown();
    doneLatch.await(5, TimeUnit.SECONDS);
    executor.shutdown();

    assertThat(results).hasSize(threadCount);
    assertThat(callCount.get()).isEqualTo(1); // Singleflight protection!
  }
}
