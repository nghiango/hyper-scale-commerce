package com.hyperscale.commerce.modules.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

private const val EXPECTED_PRODUCT_COUNT = 1000L

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogPerformanceSetupTest @Autowired constructor(private val jdbcTemplate: JdbcTemplate) {

  companion object {
    @Container
    @JvmStatic
    @ServiceConnection
    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
  }

  @Test
  fun `seeds at least 1000 products for performance testing`() {
    val count =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM catalog.products", Long::class.java) ?: 0L
    assertThat(count).isGreaterThanOrEqualTo(EXPECTED_PRODUCT_COUNT)
  }
}
