package com.hyperscale.commerce.modules.order.application

import com.hyperscale.commerce.modules.order.domain.IdempotencyRecord
import com.hyperscale.commerce.modules.order.domain.IdempotencyRepository
import com.hyperscale.commerce.modules.order.domain.IdempotencyStatus
import java.security.MessageDigest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

private const val DEFAULT_RESPONSE_OK = 200

class IdempotencyConflictException(message: String) : RuntimeException(message)

class IdempotencyPayloadMismatchException(message: String) : RuntimeException(message)

sealed interface IdempotencyDecision {
  data object Proceed : IdempotencyDecision

  data class Replay(val statusCode: Int, val body: String) : IdempotencyDecision
}

@Service
class IdempotencyService(
    private val idempotencyRepository: IdempotencyRepository,
) {

  fun computeHash(payload: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(payload.toByteArray(Charsets.UTF_8))
    return hash.joinToString("") { "%02x".format(it) }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  fun evaluate(key: String, requestHash: String): IdempotencyDecision {
    if (idempotencyRepository.tryInsertInProgress(key, requestHash)) {
      return IdempotencyDecision.Proceed
    }

    val existing = requireRecord(key)
    validatePayload(key, existing.requestHash, requestHash)
    return resolveStatusDecision(key, existing)
  }

  private fun requireRecord(key: String): IdempotencyRecord {
    return idempotencyRepository.findByKey(key)
        ?: throw IdempotencyConflictException("Concurrent request with key '$key' in progress")
  }

  private fun validatePayload(key: String, existingHash: String, currentHash: String) {
    if (existingHash != currentHash) {
      throw IdempotencyPayloadMismatchException(
          "Idempotency key '$key' reused with differing request payload")
    }
  }

  private fun resolveStatusDecision(key: String, existing: IdempotencyRecord): IdempotencyDecision {
    return when (existing.status) {
      IdempotencyStatus.IN_PROGRESS ->
          throw IdempotencyConflictException(
              "Concurrent request with key '$key' is currently in progress")
      IdempotencyStatus.COMPLETED ->
          IdempotencyDecision.Replay(
              existing.responseCode ?: DEFAULT_RESPONSE_OK,
              existing.responseBody ?: "",
          )
      IdempotencyStatus.FAILED -> {
        idempotencyRepository.markFailed(key)
        IdempotencyDecision.Proceed
      }
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  fun complete(key: String, statusCode: Int, responseBody: String) {
    idempotencyRepository.markCompleted(key, statusCode, responseBody)
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  fun fail(key: String) {
    idempotencyRepository.markFailed(key)
  }
}
