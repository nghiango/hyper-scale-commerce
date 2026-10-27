package com.hyperscale.commerce.modules.order.application;

import com.hyperscale.commerce.modules.order.domain.IdempotencyRecord;
import com.hyperscale.commerce.modules.order.domain.IdempotencyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {
  private static final int DEFAULT_RESPONSE_OK = 200;

  private final IdempotencyRepository idempotencyRepository;

  public IdempotencyService(IdempotencyRepository idempotencyRepository) {
    this.idempotencyRepository = idempotencyRepository;
  }

  public String computeHash(String payload) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public IdempotencyDecision evaluate(String key, String requestHash) {
    if (idempotencyRepository.tryInsertInProgress(key, requestHash)) {
      return IdempotencyDecision.Proceed.INSTANCE;
    }

    IdempotencyRecord existing = requireRecord(key);
    validatePayload(key, existing.requestHash(), requestHash);
    return resolveStatusDecision(key, existing);
  }

  private IdempotencyRecord requireRecord(String key) {
    IdempotencyRecord record = idempotencyRepository.findByKey(key);
    if (record == null) {
      throw new IdempotencyConflictException(
          "Concurrent request with key '" + key + "' in progress");
    }
    return record;
  }

  private void validatePayload(String key, String existingHash, String currentHash) {
    if (!existingHash.equals(currentHash)) {
      throw new IdempotencyPayloadMismatchException(
          "Idempotency key '" + key + "' reused with differing request payload");
    }
  }

  private IdempotencyDecision resolveStatusDecision(String key, IdempotencyRecord existing) {
    switch (existing.status()) {
      case IN_PROGRESS:
        throw new IdempotencyConflictException(
            "Concurrent request with key '" + key + "' is currently in progress");
      case COMPLETED:
        return new IdempotencyDecision.Replay(
            existing.responseCode() != null ? existing.responseCode() : DEFAULT_RESPONSE_OK,
            existing.responseBody() != null ? existing.responseBody() : "");
      case FAILED:
      default:
        idempotencyRepository.markFailed(key);
        return IdempotencyDecision.Proceed.INSTANCE;
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void complete(String key, int statusCode, String responseBody) {
    idempotencyRepository.markCompleted(key, statusCode, responseBody);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void fail(String key) {
    idempotencyRepository.markFailed(key);
  }
}
