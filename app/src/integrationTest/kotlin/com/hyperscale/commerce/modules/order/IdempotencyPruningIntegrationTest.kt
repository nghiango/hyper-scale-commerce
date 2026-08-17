package com.hyperscale.commerce.modules.order

import com.hyperscale.commerce.jooq.order.Tables.IDEMPOTENCY_KEYS
import com.hyperscale.commerce.modules.order.application.IdempotencyPruningService
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class IdempotencyPruningIntegrationTest
@Autowired
constructor(
    private val idempotencyPruningService: IdempotencyPruningService,
    private val dsl: DSLContext,
    private val jdbcTemplate: JdbcTemplate,
) {

  companion object {
    @Container
    @JvmStatic
    @ServiceConnection
    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
  }

  @BeforeEach
  fun clean() {
    dsl.deleteFrom(IDEMPOTENCY_KEYS).execute()
  }

  @Test
  fun `prunes only expired idempotency keys older than retention threshold`() {
    val now = Instant.now()
    val twoDaysAgo = now.minus(Duration.ofDays(2))
    val tomorrow = now.plus(Duration.ofDays(1))

    // 1. Old expired key -> SHOULD BE PRUNED
    jdbcTemplate.update(
        """
        INSERT INTO "order".idempotency_keys (key, request_hash, status, expires_at)
        VALUES ('old-key', 'hash1', 'COMPLETED', ?)
        """
            .trimIndent(),
        Timestamp.from(twoDaysAgo),
    )

    // 2. Active unexpired key -> MUST NOT BE PRUNED
    jdbcTemplate.update(
        """
        INSERT INTO "order".idempotency_keys (key, request_hash, status, expires_at)
        VALUES ('active-key', 'hash2', 'COMPLETED', ?)
        """
            .trimIndent(),
        Timestamp.from(tomorrow),
    )

    val prunedCount = idempotencyPruningService.pruneIdempotency()
    assertThat(prunedCount).isEqualTo(1)

    val remainingKeys =
        jdbcTemplate.queryForList(
            "SELECT key FROM \"order\".idempotency_keys",
            String::class.java,
        )
    assertThat(remainingKeys).containsExactly("active-key")
  }
}
