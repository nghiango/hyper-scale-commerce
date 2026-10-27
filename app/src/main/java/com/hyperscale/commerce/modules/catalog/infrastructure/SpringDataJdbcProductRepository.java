package com.hyperscale.commerce.modules.catalog.infrastructure;

import com.hyperscale.commerce.jooq.catalog.Tables;
import com.hyperscale.commerce.modules.catalog.domain.Availability;
import com.hyperscale.commerce.modules.catalog.domain.Money;
import com.hyperscale.commerce.modules.catalog.domain.Product;
import com.hyperscale.commerce.modules.catalog.domain.ProductId;
import com.hyperscale.commerce.modules.catalog.domain.ProductRepository;
import com.hyperscale.commerce.modules.catalog.domain.Sku;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class SpringDataJdbcProductRepository implements ProductRepository {
  private final ProductJdbcRepository jdbcRepository;
  private final DSLContext dsl;

  public SpringDataJdbcProductRepository(ProductJdbcRepository jdbcRepository, DSLContext dsl) {
    this.jdbcRepository = jdbcRepository;
    this.dsl = dsl;
  }

  @Override
  public Product findById(ProductId id) {
    ProductEntity entity = jdbcRepository.findByIdEntity(id.value());
    return entity != null ? toProduct(entity) : null;
  }

  @Override
  public Product findBySku(Sku sku) {
    ProductEntity entity = jdbcRepository.findBySkuEntity(sku.value());
    return entity != null ? toProduct(entity) : null;
  }

  @Override
  public List<Product> search(String query, int page, int size) {
    String pattern = searchPattern(query);
    return dsl.select(
            Tables.PRODUCTS.ID,
            Tables.PRODUCTS.SKU,
            Tables.PRODUCTS.NAME,
            Tables.PRODUCTS.DESCRIPTION,
            Tables.PRODUCTS.PRICE,
            Tables.PRODUCTS.AVAILABILITY)
        .from(Tables.PRODUCTS)
        .where(
            Tables.PRODUCTS
                .NAME
                .likeIgnoreCase(pattern)
                .or(Tables.PRODUCTS.SKU.likeIgnoreCase(pattern)))
        .orderBy(Tables.PRODUCTS.ID)
        .limit(size)
        .offset(page * size)
        .fetch(this::toProduct);
  }

  @Override
  public long count(String query) {
    String pattern = searchPattern(query);
    Long result =
        dsl.selectCount()
            .from(Tables.PRODUCTS)
            .where(
                Tables.PRODUCTS
                    .NAME
                    .likeIgnoreCase(pattern)
                    .or(Tables.PRODUCTS.SKU.likeIgnoreCase(pattern)))
            .fetchOne(0, Long.class);
    return result != null ? result : 0L;
  }

  private String searchPattern(String query) {
    return query == null || query.isBlank() ? "%" : "%" + query + "%";
  }

  private Product toProduct(ProductEntity entity) {
    return new Product(
        new ProductId(entity.id()),
        new Sku(entity.sku()),
        entity.name(),
        entity.description(),
        new Money(entity.price()),
        Availability.valueOf(entity.availability()));
  }

  private Product toProduct(Record record) {
    return new Product(
        new ProductId(record.getValue(Tables.PRODUCTS.ID)),
        new Sku(record.getValue(Tables.PRODUCTS.SKU)),
        record.getValue(Tables.PRODUCTS.NAME),
        record.getValue(Tables.PRODUCTS.DESCRIPTION),
        new Money(record.getValue(Tables.PRODUCTS.PRICE)),
        Availability.valueOf(record.getValue(Tables.PRODUCTS.AVAILABILITY)));
  }
}
