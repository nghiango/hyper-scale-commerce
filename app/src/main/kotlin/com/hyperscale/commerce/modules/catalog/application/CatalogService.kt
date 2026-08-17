package com.hyperscale.commerce.modules.catalog.application

import com.hyperscale.commerce.config.cache.CacheInvalidationService
import com.hyperscale.commerce.config.cache.L2CacheStore
import com.hyperscale.commerce.config.cache.NearCache
import com.hyperscale.commerce.modules.catalog.domain.ProductId
import com.hyperscale.commerce.modules.catalog.domain.ProductNotFoundException
import com.hyperscale.commerce.modules.catalog.domain.ProductRepository
import com.hyperscale.commerce.modules.catalog.domain.Sku
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CatalogService(
    private val productRepository: ProductRepository,
    @Autowired(required = false) meterRegistry: MeterRegistry? = null,
    @Autowired(required = false) l2Store: L2CacheStore? = null,
    @Autowired(required = false) private val invalidationService: CacheInvalidationService? = null,
) {

  private val productCache =
      NearCache<Long, ProductDto>(
          name = PRODUCT_CACHE_NAME,
          l1MaxSize = PRODUCT_CACHE_MAX_SIZE,
          l1Ttl = Duration.ofSeconds(PRODUCT_CACHE_TTL_SECONDS),
          meterRegistry = meterRegistry,
          l2Store = l2Store,
          valueClass = ProductDto::class.java,
      )

  private val listCache =
      NearCache<String, PagedProductsDto>(
          name = LIST_CACHE_NAME,
          l1MaxSize = LIST_CACHE_MAX_SIZE,
          l1Ttl = Duration.ofSeconds(LIST_CACHE_TTL_SECONDS),
          meterRegistry = meterRegistry,
          l2Store = l2Store,
          valueClass = PagedProductsDto::class.java,
      )

  init {
    invalidationService?.registerCache(productCache)
    invalidationService?.registerCache(listCache)
  }

  @Transactional(readOnly = true)
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

  @Transactional(readOnly = true)
  fun getProductById(id: Long): ProductDto {
    val cached =
        productCache.get(id) {
          val product = productRepository.findById(ProductId(id))
          product?.toDto()
        }

    return cached ?: throw ProductNotFoundException("Product with id $id not found")
  }

  @Transactional(readOnly = true)
  fun getProductBySku(sku: String): ProductDto {
    val product = productRepository.findBySku(Sku(sku))
    return product?.toDto() ?: throw ProductNotFoundException("Product with SKU $sku not found")
  }

  fun evictAll() {
    productCache.evictAll()
    listCache.evictAll()
    invalidationService?.publishInvalidation(PRODUCT_CACHE_NAME)
    invalidationService?.publishInvalidation(LIST_CACHE_NAME)
  }

  fun evictProduct(id: Long) {
    productCache.evict(id)
    listCache.evictAll()
    invalidationService?.publishInvalidation(PRODUCT_CACHE_NAME, id.toString())
    invalidationService?.publishInvalidation(LIST_CACHE_NAME)
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
    private const val PRODUCT_CACHE_NAME = "catalog_products"
    private const val LIST_CACHE_NAME = "catalog_list"
  }
}
