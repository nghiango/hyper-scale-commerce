package com.hyperscale.commerce.modules.order;

import static com.hyperscale.commerce.jooq.order.Tables.IDEMPOTENCY_KEYS;
import static org.assertj.core.api.Assertions.assertThat;

import com.hyperscale.commerce.modules.order.application.IdempotencyPruningService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public class IdempotencyPruningIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

  private final IdempotencyPruningService idempotencyPruningService;
  private final DSLContext dsl;
  private final JdbcTemplate jdbcTemplate;

  @Autowired
  public IdempotencyPruningIntegrationTest(
      IdempotencyPruningService idempotencyPruningService,
      DSLContext dsl,
      JdbcTemplate jdbcTemplate) {
    this.idempotencyPruningService = idempotencyPruningService;
    this.dsl = dsl;
    this.jdbcTemplate = jdbcTemplate;
  }

  @BeforeEach
  void clean() {
    dsl.deleteFrom(IDEMPOTENCY_KEYS).execute();
  }

  @Test
  @DisplayName("prunes only expired idempotency keys older than retention threshold")
  void prunesOnlyExpiredIdempotencyKeysOlderThanRetentionThreshold() {
    Instant now = Instant.now();
    Instant twoDaysAgo = now.minus(Duration.ofDays(2));
    Instant tomorrow = now.plus(Duration.ofDays(1));

    // 1. Old expired key -> SHOULD BE PRUNED
    jdbcTemplate.update(
        """
        INSERT INTO "order".idempotency_keys (key, request_hash, status, expires_at)
        VALUES ('old-key', 'hash1', 'COMPLETED', ?)
        """,
        Timestamp.from(twoDaysAgo));

    // 2. Active unexpired key -> MUST NOT BE PRUNED
    jdbcTemplate.update(
        """
        INSERT INTO "order".idempotency_keys (key, request_hash, status, expires_at)
        VALUES ('active-key', 'hash2', 'COMPLETED', ?)
        """,
        Timestamp.from(tomorrow));

    int prunedCount = idempotencyPruningService.pruneIdempotency();
    assertThat(prunedCount).isEqualTo(1);

    List<String> remainingKeys =
        jdbcTemplate.queryForList("SELECT key FROM \"order\".idempotency_keys", String.class);
    assertThat(remainingKeys).containsExactly("active-key");
  }
}
