package com.hyperscale.commerce.modules.catalog.infrastructure

import com.hyperscale.commerce.modules.catalog.domain.Product
import com.hyperscale.commerce.modules.catalog.domain.ProductId
import com.hyperscale.commerce.modules.catalog.domain.ProductRepository
import com.hyperscale.commerce.modules.catalog.domain.Sku
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcProductRepository(jdbcTemplate: JdbcTemplate) : ProductRepository {
  private val namedTemplate = NamedParameterJdbcTemplate(jdbcTemplate)
  private val rowMapper = ProductRowMapper()

  override fun findById(id: ProductId): Product? {
    val sql = "SELECT * FROM catalog.products WHERE id = :id"
    return namedTemplate.query(sql, mapOf("id" to id.value), rowMapper).firstOrNull()
  }

  override fun findBySku(sku: Sku): Product? {
    val sql = "SELECT * FROM catalog.products WHERE sku = :sku"
    return namedTemplate.query(sql, mapOf("sku" to sku.value), rowMapper).firstOrNull()
  }

  override fun search(query: String?, page: Int, size: Int): List<Product> {
    val pattern = searchPattern(query)
    val sql =
        """
        SELECT * FROM catalog.products
        WHERE name ILIKE :pattern OR sku ILIKE :pattern
        ORDER BY id
        LIMIT :limit OFFSET :offset
        """
            .trimIndent()
    val params =
        MapSqlParameterSource()
            .addValue("pattern", pattern)
            .addValue("limit", size)
            .addValue("offset", page * size)
    return namedTemplate.query(sql, params, rowMapper)
  }

  override fun count(query: String?): Long {
    val pattern = searchPattern(query)
    val sql =
        """
        SELECT count(*) FROM catalog.products
        WHERE name ILIKE :pattern OR sku ILIKE :pattern
        """
            .trimIndent()
    return namedTemplate.queryForObject(sql, mapOf("pattern" to pattern), Long::class.java) ?: 0L
  }

  private fun searchPattern(query: String?): String =
      if (query.isNullOrBlank()) "%" else "%${query}%"
}
