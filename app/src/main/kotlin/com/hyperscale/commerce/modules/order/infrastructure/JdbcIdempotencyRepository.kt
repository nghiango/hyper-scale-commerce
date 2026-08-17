package com.hyperscale.commerce.modules.order.infrastructure

import com.hyperscale.commerce.modules.order.domain.IdempotencyRecord
import com.hyperscale.commerce.modules.order.domain.IdempotencyRepository
import com.hyperscale.commerce.modules.order.domain.IdempotencyStatus
import java.sql.ResultSet
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcIdempotencyRepository(
    private val jdbcTemplate: JdbcTemplate,
) : IdempotencyRepository {

  override fun tryInsertInProgress(key: String, requestHash: String): Boolean {
    return try {
      val rows =
          jdbcTemplate.update(
              """
          INSERT INTO "order".idempotency_keys (key, request_hash, status)
          VALUES (?, ?, 'IN_PROGRESS')
          ON CONFLICT (key) DO NOTHING
          """
                  .trimIndent(),
              key,
              requestHash,
          )
      rows > 0
    } catch (_: DuplicateKeyException) {
      false
    }
  }

  override fun findByKey(key: String): IdempotencyRecord? {
    val results =
        jdbcTemplate.query(
            """
        SELECT key, request_hash, status, response_code, response_body, created_at, expires_at
        FROM "order".idempotency_keys
        WHERE key = ?
        """
                .trimIndent(),
            { rs, _ -> mapRow(rs) },
            key,
        )
    return results.firstOrNull()
  }

  override fun markCompleted(key: String, responseCode: Int, responseBody: String) {
    jdbcTemplate.update(
        """
        UPDATE "order".idempotency_keys
        SET status = 'COMPLETED', response_code = ?, response_body = ?
        WHERE key = ?
        """
            .trimIndent(),
        responseCode,
        responseBody,
        key,
    )
  }

  override fun markFailed(key: String) {
    jdbcTemplate.update(
        """
        DELETE FROM "order".idempotency_keys
        WHERE key = ?
        """
            .trimIndent(),
        key,
    )
  }

  private fun mapRow(rs: ResultSet): IdempotencyRecord {
    return IdempotencyRecord(
        key = rs.getString("key"),
        requestHash = rs.getString("request_hash"),
        status = IdempotencyStatus.valueOf(rs.getString("status")),
        responseCode = rs.getObject("response_code") as? Int,
        responseBody = rs.getString("response_body"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        expiresAt = rs.getTimestamp("expires_at").toInstant(),
    )
  }
}
