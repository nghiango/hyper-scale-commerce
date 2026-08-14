package com.hyperscale.commerce.modules.catalog.infrastructure

import com.hyperscale.commerce.jooq.catalog.Tables.PRODUCTS
import com.hyperscale.commerce.modules.catalog.domain.Availability
import com.hyperscale.commerce.modules.catalog.domain.Money
import com.hyperscale.commerce.modules.catalog.domain.Product
import com.hyperscale.commerce.modules.catalog.domain.ProductId
import com.hyperscale.commerce.modules.catalog.domain.ProductRepository
import com.hyperscale.commerce.modules.catalog.domain.Sku
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository

@Repository
class SpringDataJdbcProductRepository(
    private val jdbcRepository: ProductJdbcRepository,
    private val dsl: DSLContext,
) : ProductRepository {

  override fun findById(id: ProductId): Product? =
      jdbcRepository.findByIdEntity(id.value)?.toProduct()

  override fun findBySku(sku: Sku): Product? =
      jdbcRepository.findBySkuEntity(sku.value)?.toProduct()

  override fun search(query: String?, page: Int, size: Int): List<Product> {
    val pattern = searchPattern(query)
    return dsl.select(
            PRODUCTS.ID,
            PRODUCTS.SKU,
            PRODUCTS.NAME,
            PRODUCTS.DESCRIPTION,
            PRODUCTS.PRICE,
            PRODUCTS.AVAILABILITY,
        )
        .from(PRODUCTS)
        .where(PRODUCTS.NAME.likeIgnoreCase(pattern).or(PRODUCTS.SKU.likeIgnoreCase(pattern)))
        .orderBy(PRODUCTS.ID)
        .limit(size)
        .offset(page * size)
        .fetch { record -> record.toProduct() }
  }

  override fun count(query: String?): Long {
    val pattern = searchPattern(query)
    return dsl.selectCount()
        .from(PRODUCTS)
        .where(PRODUCTS.NAME.likeIgnoreCase(pattern).or(PRODUCTS.SKU.likeIgnoreCase(pattern)))
        .fetchOne(0, Long::class.java) ?: 0L
  }

  private fun searchPattern(query: String?): String =
      if (query.isNullOrBlank()) "%" else "%${query}%"

  private fun ProductEntity.toProduct(): Product =
      Product(
          id = ProductId(id),
          sku = Sku(sku),
          name = name,
          description = description,
          price = Money(price),
          availability = Availability.valueOf(availability),
      )

  private fun Record.toProduct(): Product =
      Product(
          id = ProductId(getValue(PRODUCTS.ID)),
          sku = Sku(getValue(PRODUCTS.SKU)),
          name = getValue(PRODUCTS.NAME),
          description = getValue(PRODUCTS.DESCRIPTION),
          price = Money(getValue(PRODUCTS.PRICE)),
          availability = Availability.valueOf(getValue(PRODUCTS.AVAILABILITY)),
      )
}
