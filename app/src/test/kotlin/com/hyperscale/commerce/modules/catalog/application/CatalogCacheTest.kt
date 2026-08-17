package com.hyperscale.commerce.modules.catalog.application

import com.hyperscale.commerce.modules.catalog.domain.Availability
import com.hyperscale.commerce.modules.catalog.domain.Money
import com.hyperscale.commerce.modules.catalog.domain.Product
import com.hyperscale.commerce.modules.catalog.domain.ProductId
import com.hyperscale.commerce.modules.catalog.domain.ProductRepository
import com.hyperscale.commerce.modules.catalog.domain.Sku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class CatalogCacheTest {

  @Test
  fun `repeated getProductById returns cached result without repository call`() {
    val repository = mock(ProductRepository::class.java)
    val service = CatalogService(repository)

    val product =
        Product(
            id = ProductId(101L),
            sku = Sku("CACHE-SKU-1"),
            name = "Cached Product",
            description = "Description",
            price = Money(4999),
            availability = Availability.IN_STOCK,
        )

    `when`(repository.findById(ProductId(101L))).thenReturn(product)

    val res1 = service.getProductById(101L)
    val res2 = service.getProductById(101L)
    val res3 = service.getProductById(101L)

    assertThat(res1.name).isEqualTo("Cached Product")
    assertThat(res2.name).isEqualTo("Cached Product")
    assertThat(res3.name).isEqualTo("Cached Product")

    // Repository should only be queried once due to Caffeine caching
    verify(repository, times(1)).findById(ProductId(101L))
  }

  @Test
  fun `evictProduct clears cache and re-queries repository on subsequent get`() {
    val repository = mock(ProductRepository::class.java)
    val service = CatalogService(repository)

    val product =
        Product(
            id = ProductId(202L),
            sku = Sku("CACHE-SKU-2"),
            name = "Evictable Product",
            description = "Description",
            price = Money(1999),
            availability = Availability.IN_STOCK,
        )

    `when`(repository.findById(ProductId(202L))).thenReturn(product)

    service.getProductById(202L)
    verify(repository, times(1)).findById(ProductId(202L))

    service.evictProduct(202L)
    service.getProductById(202L)

    verify(repository, times(2)).findById(ProductId(202L))
  }

  @Test
  fun `singleflight stampede protection ensures concurrent callers execute loader only once`() {
    val repository = mock(ProductRepository::class.java)
    val callCount = AtomicInteger(0)

    `when`(repository.findById(ProductId(303L))).thenAnswer {
      Thread.sleep(50) // simulate database latency
      callCount.incrementAndGet()
      Product(
          id = ProductId(303L),
          sku = Sku("STAMPEDE-SKU"),
          name = "Stampede Product",
          description = "Description",
          price = Money(9999),
          availability = Availability.IN_STOCK,
      )
    }

    val service = CatalogService(repository)
    val threadCount = 10
    val executor = Executors.newFixedThreadPool(threadCount)
    val startLatch = CountDownLatch(1)
    val doneLatch = CountDownLatch(threadCount)

    val results = mutableListOf<ProductDto>()

    repeat(threadCount) {
      executor.submit {
        startLatch.await()
        val dto = service.getProductById(303L)
        synchronized(results) { results.add(dto) }
        doneLatch.countDown()
      }
    }

    startLatch.countDown()
    doneLatch.await(5, TimeUnit.SECONDS)
    executor.shutdown()

    assertThat(results).hasSize(threadCount)
    assertThat(callCount.get()).isEqualTo(1) // Singleflight protection!
  }
}
