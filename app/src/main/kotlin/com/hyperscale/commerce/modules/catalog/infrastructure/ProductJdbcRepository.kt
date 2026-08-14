package com.hyperscale.commerce.modules.catalog.infrastructure

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param

interface ProductJdbcRepository : CrudRepository<ProductEntity, Long> {
  @Query("SELECT * FROM catalog.products WHERE id = :id")
  fun findByIdEntity(@Param("id") id: Long): ProductEntity?

  @Query("SELECT * FROM catalog.products WHERE sku = :sku")
  fun findBySkuEntity(@Param("sku") sku: String): ProductEntity?
}
