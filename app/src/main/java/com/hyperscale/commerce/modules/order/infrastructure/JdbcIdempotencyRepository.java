package com.hyperscale.commerce.modules.order.infrastructure;

import com.hyperscale.commerce.modules.order.domain.IdempotencyRecord;
import com.hyperscale.commerce.modules.order.domain.IdempotencyRepository;
import com.hyperscale.commerce.modules.order.domain.IdempotencyStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdempotencyRepository implements IdempotencyRepository {
  private final JdbcTemplate jdbcTemplate;

  public JdbcIdempotencyRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public boolean tryInsertInProgress(String key, String requestHash) {
    try {
      int rows =
          jdbcTemplate.update(
              """
          INSERT INTO "order".idempotency_keys (key, request_hash, status)
          VALUES (?, ?, 'IN_PROGRESS')
          ON CONFLICT (key) DO NOTHING
          """,
              key,
              requestHash);
      return rows > 0;
    } catch (DuplicateKeyException ex) {
      return false;
    }
  }

  @Override
  public IdempotencyRecord findByKey(String key) {
    List<IdempotencyRecord> results =
        jdbcTemplate.query(
            """
        SELECT key, request_hash, status, response_code, response_body, created_at, expires_at
        FROM "order".idempotency_keys
        WHERE key = ?
        """,
            (rs, rowNum) -> mapRow(rs),
            key);
    return results.isEmpty() ? null : results.get(0);
  }

  @Override
  public void markCompleted(String key, int responseCode, String responseBody) {
    jdbcTemplate.update(
        """
        UPDATE "order".idempotency_keys
        SET status = 'COMPLETED', response_code = ?, response_body = ?
        WHERE key = ?
        """,
        responseCode,
        responseBody,
        key);
  }

  @Override
  public void markFailed(String key) {
    jdbcTemplate.update(
        """
        DELETE FROM "order".idempotency_keys
        WHERE key = ?
        """,
        key);
  }

  @Override
  public int pruneExpired(Instant olderThan, int batchSize) {
    return jdbcTemplate.update(
        """
        DELETE FROM "order".idempotency_keys
        WHERE key IN (
          SELECT key FROM "order".idempotency_keys
          WHERE expires_at < ?
          LIMIT ?
        )
        """,
        Timestamp.from(olderThan),
        batchSize);
  }

  private IdempotencyRecord mapRow(ResultSet rs) throws SQLException {
    return new IdempotencyRecord(
        rs.getString("key"),
        rs.getString("request_hash"),
        IdempotencyStatus.valueOf(rs.getString("status")),
        (Integer) rs.getObject("response_code"),
        rs.getString("response_body"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("expires_at").toInstant());
  }
}
