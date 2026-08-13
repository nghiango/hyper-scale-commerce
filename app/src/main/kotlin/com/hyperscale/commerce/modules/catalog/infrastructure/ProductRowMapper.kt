package com.hyperscale.commerce.modules.catalog.infrastructure

import com.hyperscale.commerce.modules.catalog.domain.Availability
import com.hyperscale.commerce.modules.catalog.domain.Money
import com.hyperscale.commerce.modules.catalog.domain.Product
import com.hyperscale.commerce.modules.catalog.domain.ProductId
import com.hyperscale.commerce.modules.catalog.domain.Sku
import java.sql.ResultSet
import org.springframework.jdbc.core.RowMapper

class ProductRowMapper : RowMapper<Product> {
  override fun mapRow(rs: ResultSet, rowNum: Int): Product =
      Product(
          id = ProductId(rs.getLong("id")),
          sku = Sku(rs.getString("sku")),
          name = rs.getString("name"),
          description = rs.getString("description"),
          price = Money(rs.getInt("price")),
          availability = Availability.valueOf(rs.getString("availability")),
      )
}
