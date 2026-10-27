package com.hyperscale.commerce.config.local;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "kubernetes", "perf", "default", "!test"})
public class CatalogLocalSeedData implements CommandLineRunner {

  private static final int PRODUCT_COUNT = 1000;
  private static final int SKU_NUMBER_WIDTH = 5;
  private static final int BASE_PRICE = 1000;
  private static final int PRICE_VARIATION_MODULUS = 1000;

  private final NamedParameterJdbcTemplate namedTemplate;

  public CatalogLocalSeedData(JdbcTemplate jdbcTemplate) {
    this.namedTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
  }

  @Override
  public void run(String... args) {
    Long count =
        namedTemplate.queryForObject("SELECT COUNT(*) FROM catalog.products", Map.of(), Long.class);
    if (count == null) {
      count = 0L;
    }

    if (count >= PRODUCT_COUNT) {
      return;
    }

    List<String> keywords =
        List.of(
            "Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel", "India",
            "Juliet");

    List<SqlParameterSource> batch = new ArrayList<>();
    for (int i = 0; i < PRODUCT_COUNT; i++) {
      int index = i + 1;
      String keyword = keywords.get(i % keywords.size());
      batch.add(
          new MapSqlParameterSource()
              .addValue("sku", "PERF-SKU-" + String.format("%0" + SKU_NUMBER_WIDTH + "d", index))
              .addValue("name", "Performance Product " + index + " with " + keyword)
              .addValue("description", "A performance test product for " + keyword)
              .addValue("price", BASE_PRICE + index % PRICE_VARIATION_MODULUS)
              .addValue("availability", "IN_STOCK"));
    }

    String sql =
        """
        INSERT INTO catalog.products (sku, name, description, price, availability, created_at, updated_at)
        VALUES (:sku, :name, :description, :price, :availability, now(), now())""";

    namedTemplate.batchUpdate(sql, batch.toArray(new SqlParameterSource[0]));
  }
}
