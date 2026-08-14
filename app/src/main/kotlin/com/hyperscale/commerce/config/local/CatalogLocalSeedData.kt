package com.hyperscale.commerce.config.local

import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import org.springframework.stereotype.Component

@Component
@Profile("local")
class CatalogLocalSeedData(jdbcTemplate: JdbcTemplate) : CommandLineRunner {
  private val namedTemplate = NamedParameterJdbcTemplate(jdbcTemplate)

  override fun run(vararg args: String) {
    val count =
        namedTemplate.queryForObject(
            "SELECT COUNT(*) FROM catalog.products", emptyMap<String, Any>(), Long::class.java)
            ?: 0L

    if (count >= PRODUCT_COUNT) {
      return
    }

    val keywords =
        listOf(
            "Alpha",
            "Bravo",
            "Charlie",
            "Delta",
            "Echo",
            "Foxtrot",
            "Golf",
            "Hotel",
            "India",
            "Juliet",
        )

    val batch = mutableListOf<SqlParameterSource>()
    repeat(PRODUCT_COUNT) { i ->
      val index = i + 1
      val keyword = keywords[i % keywords.size]
      batch.add(
          MapSqlParameterSource()
              .addValue("sku", "PERF-SKU-${index.toString().padStart(SKU_NUMBER_WIDTH, '0')}")
              .addValue("name", "Performance Product $index with $keyword")
              .addValue("description", "A performance test product for $keyword")
              .addValue("price", BASE_PRICE + (index % PRICE_VARIATION_MODULUS))
              .addValue("availability", "IN_STOCK"))
    }

    val sql =
        """
        INSERT INTO catalog.products (sku, name, description, price, availability, created_at, updated_at)
        VALUES (:sku, :name, :description, :price, :availability, now(), now())
        """
            .trimIndent()

    namedTemplate.batchUpdate(sql, batch.toTypedArray())
  }

  companion object {
    private const val PRODUCT_COUNT = 1000
    private const val SKU_NUMBER_WIDTH = 5
    private const val BASE_PRICE = 1000
    private const val PRICE_VARIATION_MODULUS = 1000
  }
}
