package com.hyperscale.commerce.modules.catalog.infrastructure;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ProductJdbcRepository extends CrudRepository<ProductEntity, Long> {
  @Query("SELECT * FROM catalog.products WHERE id = :id")
  ProductEntity findByIdEntity(@Param("id") long id);

  @Query("SELECT * FROM catalog.products WHERE sku = :sku")
  ProductEntity findBySkuEntity(@Param("sku") String sku);
}
