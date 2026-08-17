package com.hyperscale.commerce.modules.catalog.application

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.hyperscale.commerce.modules.catalog.domain.ProductId
import com.hyperscale.commerce.modules.catalog.domain.ProductNotFoundException
import com.hyperscale.commerce.modules.catalog.domain.ProductRepository
import com.hyperscale.commerce.modules.catalog.domain.Sku
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import java.time.Duration
import java.util.Optional
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class CatalogService(
    private val productRepository: ProductRepository,
    @Autowired(required = false) meterRegistry: MeterRegistry? = null,
) {

  private val productCache: Cache<Long, Optional<ProductDto>> =
      Caffeine.newBuilder()
          .maximumSize(PRODUCT_CACHE_MAX_SIZE)
          .expireAfterWrite(Duration.ofSeconds(PRODUCT_CACHE_TTL_SECONDS))
          .recordStats()
          .build()

  private val listCache: Cache<String, PagedProductsDto> =
      Caffeine.newBuilder()
          .maximumSize(LIST_CACHE_MAX_SIZE)
          .expireAfterWrite(Duration.ofSeconds(LIST_CACHE_TTL_SECONDS))
          .recordStats()
          .build()

  init {
    if (meterRegistry != null) {
      CaffeineCacheMetrics.monitor(meterRegistry, productCache, "catalog_products")
      CaffeineCacheMetrics.monitor(meterRegistry, listCache, "catalog_list")
    }
  }

  fun listProducts(query: String?, page: Int, size: Int): PagedProductsDto {
    require(page >= MIN_PAGE) { "Page must not be negative" }
    require(size in MIN_SIZE..MAX_SIZE) { "Size must be between $MIN_SIZE and $MAX_SIZE" }

    val cacheKey = "${query ?: ""}:$page:$size"
    return listCache.get(cacheKey) {
      val total = productRepository.count(query)
      val products = productRepository.search(query, page, size)
      PagedProductsDto(
          total = total,
          items = products.map { it.toDto() },
      )
    }!!
  }

  fun getProductById(id: Long): ProductDto {
    val cached =
        productCache.get(id) {
          val product = productRepository.findById(ProductId(id))
          Optional.ofNullable(product?.toDto())
        }

    return cached?.orElse(null) ?: throw ProductNotFoundException("Product with id $id not found")
  }

  fun getProductBySku(sku: String): ProductDto {
    val product = productRepository.findBySku(Sku(sku))
    return product?.toDto() ?: throw ProductNotFoundException("Product with SKU $sku not found")
  }

  fun evictAll() {
    productCache.invalidateAll()
    listCache.invalidateAll()
  }

  fun evictProduct(id: Long) {
    productCache.invalidate(id)
    listCache.invalidateAll()
  }

  private fun com.hyperscale.commerce.modules.catalog.domain.Product.toDto(): ProductDto =
      ProductDto(
          id = id.value,
          sku = sku.value,
          name = name,
          description = description,
          price = price.amount,
          availability = availability.name,
      )

  companion object {
    private const val MIN_PAGE = 0
    private const val MIN_SIZE = 1
    private const val MAX_SIZE = 100
    private const val PRODUCT_CACHE_MAX_SIZE = 10_000L
    private const val PRODUCT_CACHE_TTL_SECONDS = 60L
    private const val LIST_CACHE_MAX_SIZE = 2_000L
    private const val LIST_CACHE_TTL_SECONDS = 30L
  }
}
