package com.hyperscale.commerce.modules.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class CatalogPerformanceSetupTest {

  private static final long EXPECTED_PRODUCT_COUNT = 1000L;

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

  private final JdbcTemplate jdbcTemplate;

  @Autowired
  public CatalogPerformanceSetupTest(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Test
  @DisplayName("seeds at least 1000 products for performance testing")
  void seedsAtLeast1000ProductsForPerformanceTesting() {
    Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM catalog.products", Long.class);
    assertThat(count != null ? count : 0L).isGreaterThanOrEqualTo(EXPECTED_PRODUCT_COUNT);
  }
}
